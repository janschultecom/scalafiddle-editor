package controllers

import javax.inject.{Inject, Named}

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.mohiva.play.silhouette.api.{LogoutEvent, Silhouette}
import com.mohiva.play.silhouette.impl.providers.SocialProviderRegistry
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import play.api.{Configuration, Environment, Logger, Mode}
import slick.backend.DatabaseConfig
import slick.driver.JdbcProfile
import slick.jdbc.meta.MTable
import upickle.default._
import upickle.{Js, json}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import scalafiddle.server._
import scalafiddle.server.dao.{Fiddle, FiddleDAL}
import scalafiddle.server.models.User
import utils.auth.{AllLoginProviders, DefaultEnv}
import scalafiddle.shared.{Api, FiddleData, Library, UserInfo}

object Router extends autowire.Server[Js.Value, Reader, Writer] {
  override def read[R: Reader](p: Js.Value) = readJs[R](p)
  override def write[R: Writer](r: R) = writeJs(r)
}

class Application @Inject()(
  implicit val config: Configuration,
  env: Environment,
  silhouette: Silhouette[DefaultEnv],
  socialProviderRegistry: SocialProviderRegistry,
  actorSystem: ActorSystem,
  @Named("persistence") persistence: ActorRef
) extends Controller {
  implicit val timeout = Timeout(15.seconds)
  val log = Logger(getClass)
  val libraries = loadLibraries(config.getString("scalafiddle.librariesURL").get)
  val defaultSource = config.getString("scalafiddle.defaultSource").get

  if (env.mode != Mode.Prod)
    createTables()

  def index(fiddleId: String, version: String) = Action.async {
    loadFiddle(fiddleId, version.toInt).map {
      case Success(fd) =>
        val fdJson = write(fd)
        Ok(views.html.index("ScalaFiddle", fdJson)).withHeaders(CACHE_CONTROL -> "max-age=3600")
      case Failure(ex) =>
        NotFound
    }
  }

  def signOut = silhouette.SecuredAction.async { implicit request =>
    val result = Redirect(routes.Application.index("", "0"))
    silhouette.env.eventBus.publish(LogoutEvent(request.identity, request))
    silhouette.env.authenticatorService.discard(request.authenticator, result)
  }

  def rawFiddle(fiddleId: String, version: String) = Action.async {
    loadFiddle(fiddleId, version.toInt).map {
      case Success(fd) =>
        // create a source code file for embedded ScalaFiddle
        val nameOpt = fd.name match {
          case empty if empty.replaceAll("\\s", "").isEmpty => None
          case nonEmpty => Some(nonEmpty.replaceAll("\\s", " "))
        }
        val sourceCode = new StringBuilder()
        sourceCode.append(fd.sourceCode)
        val allLibs = fd.libraries.flatMap(lib => Library.stringify(lib) +: lib.extraDeps)
        sourceCode.append(allLibs.map(lib => s"// $$FiddleDependency $lib").mkString("\n", "\n", "\n"))
        nameOpt.foreach(name => sourceCode.append(s"// $$FiddleName $name\n"))

        Ok(sourceCode.toString).withHeaders(CACHE_CONTROL -> "max-age=3600")
      case Failure(ex) =>
        NotFound
    }
  }

  def libraryListing = Action {
    val libStrings = libraries.flatMap(lib => Library.stringify(lib) +: lib.extraDeps)
    Ok(write(libStrings)).as("application/json").withHeaders(CACHE_CONTROL -> "max-age=60")
  }

  def resultFrame = Action { request =>
    Ok(views.html.resultframe()).withHeaders(CACHE_CONTROL -> "max-age=3600")
  }

  def fiddleList = Action.async { implicit request =>
    ask(persistence, GetFiddleInfo).mapTo[Try[Seq[FiddleInfo]]].map {
      case Success(info) =>
        // remove duplicates
        val fiddles = info.groupBy(_.id.id).values.map(_.sortBy(_.id.version).last).toVector
        Ok(views.html.listfiddles(fiddles)).withHeaders(CACHE_CONTROL -> "max-age=10")
      case Failure(ex) =>
        NotFound
    }
  }

  val loginProviders = config.getStringSeq("scalafiddle.loginProviders").get.map(AllLoginProviders.providers)

  def autowireApi(path: String) = silhouette.UserAwareAction.async {
    implicit request =>
      val apiService: Api = new ApiService(persistence, request.identity, loginProviders)

      // get the request body as JSON
      val b = request.body.asText.get

      // call Autowire route
      Router.route[Api](apiService)(
        autowire.Core.Request(path.split("/"), json.read(b).asInstanceOf[Js.Obj].value.toMap)
      ).map(buffer => {
        val data = json.write(buffer)
        Ok(data)
      })
  }

  case class LibraryVersion(
    scalaVersions: Seq[String],
    extraDeps: Seq[String]
  )

  case class LibraryDef(
    name: String,
    organization: String,
    artifact: String,
    doc: String,
    versions: Map[String, LibraryVersion],
    compileTimeOnly: Boolean
  )

  case class LibraryGroup(
    group: String,
    libraries: Seq[LibraryDef]
  )

  def createDocURL(doc: String): String = {
    val githubRef = """([^/]+)/([^/]+)""".r
    doc match {
      case githubRef(org, lib) => s"https://github.com/$org/$lib"
      case _ => doc
    }
  }

  def loadLibraries(uri: String): Seq[Library] = {
    val data = if (uri.startsWith("file:")) {
      // load from file system
      scala.io.Source.fromFile(uri.drop(5), "UTF-8").mkString
    } else {
      env.resourceAsStream(uri).map(s => scala.io.Source.fromInputStream(s, "UTF-8").mkString).get
    }
    val libGroups = read[Seq[LibraryGroup]](data)
    for {
      (group, idx) <- libGroups.zipWithIndex
      lib <- group.libraries
      (version, versionDef) <- lib.versions
    } yield {
      Library(lib.name, lib.organization, lib.artifact, version, lib.compileTimeOnly, versionDef.scalaVersions, versionDef.extraDeps, f"$idx%02d:${group.group}", createDocURL(lib.doc))
    }
  }

  def loadFiddle(id: String, version: Int): Future[Try[FiddleData]] = {
    if (id == "") {
      // build default fiddle data
      val (source, libs) = parseFiddle(defaultSource)
      Future(Success(FiddleData("", "", source, libs, Seq.empty, libraries, None)))
    } else {
      ask(persistence, FindFiddle(id, version)).mapTo[Try[Fiddle]].flatMap {
        case Success(f) if f.user == "anonymous" =>
          Future.successful(Success(FiddleData(f.name, f.description, f.sourceCode, f.libraries.flatMap(findLibrary), Seq.empty, libraries, None)))
        case Success(f) =>
          ask(persistence, FindUser(f.user)).mapTo[Try[User]].map {
            case Success(u) =>
              val user = UserInfo(u.userID, u.name.getOrElse("Anonymous"), u.avatarURL, loggedIn = false)
              Success(FiddleData(f.name, f.description, f.sourceCode, f.libraries.flatMap(findLibrary), Seq.empty, libraries, Some(user)))
            case _ =>
              Success(FiddleData(f.name, f.description, f.sourceCode, f.libraries.flatMap(findLibrary), Seq.empty, libraries, None))
          }
        case Failure(e) =>
          Future.successful(Failure(e))
      }
    }
  }

  val repoSJSRE = """([^ %]+) *%%% *([^ %]+) *% *([^ %]+)""".r
  val repoRE = """([^ %]+) *%% *([^ %]+) *% *([^ %]+)""".r

  def findLibrary(libDef: String): Option[Library] = libDef match {
    case repoSJSRE(group, artifact, version) =>
      libraries.find(l => l.organization == group && l.artifact == artifact && l.version == version && !l.compileTimeOnly)
    case repoRE(group, artifact, version) =>
      libraries.find(l => l.organization == group && l.artifact == artifact && l.version == version && l.compileTimeOnly)
    case _ =>
      None
  }

  def parseFiddle(source: String): (String, Seq[Library]) = {
    val dependencyRE = """ *// \$FiddleDependency (.+)"""
    val lines = source.split("\n")
    val (libLines, codeLines) = lines.partition(_.matches(dependencyRE))
    val libs = libLines.flatMap(findLibrary)
    (codeLines.mkString("\n"), libs)
  }

  def createTables() = {
    log.debug(s"Creating missing tables")
    // create tables
    val dbConfig = DatabaseConfig.forConfig[JdbcProfile](config.getString("scalafiddle.dbConfig").get)
    val db = dbConfig.db
    val dal = new FiddleDAL(dbConfig.driver)
    import dal.driver.api._

    def createTableIfNotExists(tables: Seq[TableQuery[_ <: Table[_]]]): Future[Any] = {
      // create tables in order, waiting for previous "create" to complete before running next
      tables.foldLeft(Future.successful(())) { (f, table) =>
        f.flatMap(_ => db.run(MTable.getTables(table.baseTableRow.tableName)).flatMap { result =>
          if (result.isEmpty) {
            log.debug(s"Creating table: ${table.baseTableRow.tableName}")
            db.run(table.schema.create)
          } else {
            Future.successful(())
          }
        })
      }
    }
    Await.result(createTableIfNotExists(Seq(dal.fiddles, dal.users)), Duration.Inf)
  }
}
