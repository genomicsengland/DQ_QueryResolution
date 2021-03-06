package controllers

import java.io.{PrintWriter, StringWriter}
import javax.inject._

import controllers.UiUtils._
import dao.Searching.{SearchRequest, SearchResult}
import domain._
import org.pac4j.core.config.Config
import org.pac4j.core.profile.{CommonProfile, ProfileManager}
import org.pac4j.play.PlayWebContext
import org.pac4j.play.scala.Security
import org.pac4j.play.store.{PlayCacheSessionStore, PlaySessionStore}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.Json
import play.api.libs.json.Json._
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import play.libs.concurrent.HttpExecutionContext
import services.{IssueTrackingService, MailService, ReportCalculator}

import scala.collection.JavaConversions
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@Singleton
class DqController @Inject()(override val config: Config, override val playSessionStore: PlaySessionStore, override val ec: HttpExecutionContext,
                            issueTracking: IssueTrackingService, saveIssueHelper: SaveIssueHelper, mailService: MailService,
                             configuration: Configuration)
                            (implicit executionContext: ExecutionContext) extends Controller with Security[CommonProfile] {

  val log: Logger = LoggerFactory.getLogger(getClass)
  val baseUrl: String = configuration.getString("play.http.context").get

  //pac4j profiles
  def getProfiles(implicit request: RequestHeader): List[CommonProfile] = {
    val webContext = new PlayWebContext(request, playSessionStore)
    val sessionStore: PlayCacheSessionStore = playSessionStore.asInstanceOf[PlayCacheSessionStore]
//    sessionStore.setTimeout(10)

//    config.getSessionStore.asInstanceOf[PlayCacheSessionStore].setTimeout(12)
    val profileManager = new ProfileManager[CommonProfile](webContext)
    val profiles = profileManager.getAll(true)

//    println("got1=" + profiles.get(0).getEmail)
//    println("got2=" + profiles.get(0).getFirstName)
//    println("got3=" + profiles.get(0).getFamilyName)
//    println("got4=" + profiles.get(0).getDisplayName)
//    println("got5=" + profiles.get(0).getUsername)

    JavaConversions.asScalaBuffer(profiles).toList
  }

  def getUserName(implicit request: RequestHeader): String = {
//    "rr"
    if( getProfiles(request).isEmpty) throw new SecurityException("no profile available")
    else getProfiles(request).head.getId
  }

  /** Create an Action to render an HTML page with a welcome message.
    * The configuration in the `routes` file means that this method
    * will be called when the application receives a `GET` request with
    * a path of `/`.
    */
  def listIssues = Action.async { implicit req =>
    issueTracking.allIssues.map(issues => Ok(views.html.issues(issues, getProfiles(req), baseUrl)))
  }

  //TODO : To be removed (Just a temporary method to create a table using model)
  def tmpMethod = Action.async { implicit req =>
    issueTracking.tmpMethod.map(_ =>
      Redirect(routes.DqController.container()))
  }



  def listAjaxAsync = Action.async(parse.tolerantFormUrlEncoded) { implicit req =>

    getProfiles(req)
    val searchRequest: SearchRequest = DatatablesRequestBuilder.build(req.body ++ req.queryString)

    val findResult: Future[SearchResult[IssueView]] = issueTracking.findBySearchRequest(searchRequest)

    findResult.map {
      pageResult => {
        val json = Json.obj(
          "draw" -> searchRequest.uiRequestToken,
          "recordsTotal" -> pageResult.total.toString,
          "recordsFiltered" -> pageResult.total.toString,
          "data" -> Json.toJson(pageResult.items)
        )
        Ok(json)
      }
    }
  }


  def listGmcs = Action.async { implicit req =>
    issueTracking.listGmcs.map(gmcs => Ok(gmcsToJson(gmcs)))
  }

  def listOrigins = Action.async { implicit req =>
    issueTracking.listOrigins.map(origins => Ok(originsToJson(origins)))
  }


  def listPriorities = Action.async { implicit req =>
    issueTracking.listPriorities.map(origins => Ok(prioritiesToJson(origins)))
  }


  def container = Action {
    Ok(views.html.container(baseUrl))
  }



  def sendNotifications() = Action.async { implicit req =>
    val selected = param(req, "selectedIssues").get
    log.debug(s"selected issues=$selected")
    val issueIds = selected.split(",").toList

    val findResult: Future[SearchResult[Issue]] = issueTracking.findByIssueIds(issueIds)
    var total: Int = 0
    var selectedIssues: Seq[Issue] = null

    findResult.onComplete{
      case Success(searchResult) => {
        total = searchResult.total
        selectedIssues = searchResult.items
        mailService.send(selectedIssues)

      }
      case Failure(e) => {e.printStackTrace}
    }

    Future(Ok(Json.toJson("sent email? or queued?")))
  }



  def exportSelected = Action.async(parse.tolerantFormUrlEncoded) { implicit req =>
    val selected = param(req.queryString, "exportSelectedIds").get
    log.debug(s"exportSelectedIds=$selected")
    val issueIds = selected.split(",").toList

    buildAndExportCsv(issueTracking.findIssueViewByIssueIds(issueIds))
  }


  def exportFiltered = Action.async(parse.tolerantFormUrlEncoded) { implicit req =>

    val searchRequest: SearchRequest = DatatablesRequestBuilder.build(req.body ++ req.queryString)

    buildAndExportCsv(issueTracking.findBySearchRequest(searchRequest))
  }


  private def buildAndExportCsv(findResult: Future[SearchResult[IssueView]]): Future[Result] = {
    val csv = new StringBuilder(IssueView.csvHeaderForUI + "\n")

    findResult.map {
      pageResult => {
        pageResult.items.map(issue => csv ++= (issue.toCsvForUI + "\n"))

        log.info(s"export returned ${pageResult.total} rows")

        Ok(csv.toString()).as("text/csv").withHeaders(
          CONTENT_DISPOSITION -> "attachment;filename=\"ExportedIssues.csv\"")
      }
    }
  }


  def upload = Action(parse.multipartFormData) { implicit request =>
    val result = Try {

      val filePart: FilePart[TemporaryFile] = request.body.files.head
      import java.io.File
      val filename = filePart.filename
      val contentType = filePart.contentType
      val toFile: File = new File(s"/tmp/filePart/$filename")
      toFile.delete()     //previous
      filePart.ref.moveTo(toFile)
      val failures: List[(Int, Throwable)] = issueTracking.importFile(toFile)

      if(failures.length == 0) Ok("OK")
      else {
        Ok(failuresToJsonRowIds(failures))
      }
    }
    result.getOrElse {
      val e: Throwable = result.failed.get
      val sw = new StringWriter
      e.printStackTrace(new PrintWriter(sw))
      log.error(s"File upload failed ${sw.toString}")
      Ok("File upload failed")
    }
  }



  def changeStatus = Action.async(parse.multipartFormData) { implicit req =>
    val result = Try {

      val body: Map[String, Seq[String]] = req.body.dataParts

      val selected = param(body, "selectedIssues").get
      val issueIds = selected.split(",").toList

      val change = param(body, "change").get
      val newStatus = domain.Status.allStatuses.find(_.toString == change).get
      log.debug(s"selected issues=$selected change=$change newStatus=$newStatus")

      val failures: Future[List[(String, Throwable)]] = issueTracking.changeStatus(newStatus,issueIds, getUserName(req))

      val eventualResult: Future[Result] = failures.map { failed => {
        if (failed.isEmpty == 0) Ok("OK")
        else {
          log.error("failed.length=" + failed.length)
          Ok(failuresToJsonIssueIds(failed))
        }
      }
      }
      eventualResult
    }
    result.getOrElse {
      val e: Throwable = result.failed.get
      val sw = new StringWriter
      e.printStackTrace(new PrintWriter(sw))
      log.error(s"Change Status failed ${sw.toString}")
      Future(Ok(s"Change Status failed - ${sw.toString}"))
    }
  }


  def report = Action { implicit req =>
    Ok(reportsToJson(ReportCalculator.statistics(issueTracking)))
  }


  def exportReport = Action(parse.tolerantFormUrlEncoded) { implicit req =>

    val issueStats = ReportCalculator.statistics(issueTracking)
    val csv = new StringBuilder(ReportCalculator.csvHeaderForUI + "\n")

    issueStats.map {
      issueStat => {
        csv ++= (issueStat.toCsvForUI + "\n")
      }
    }
    log.info(s"exportReport returned ${issueStats.length} rows")

    Ok(csv.toString()).as("text/csv").withHeaders(
      CONTENT_DISPOSITION -> "attachment;filename=\"ExportReport.csv\"")
  }


  def queryChain = Action.async(parse.multipartFormData) { implicit req =>
    val result = Try {

      val body: Map[String, Seq[String]] = req.body.dataParts

      val selected = param(body, "selectedIssue").get
      log.debug(s"selected issue=$selected")

      val eventualQueryChains: Future[Seq[QueryChain]] = issueTracking.queryChain(selected)

      val eventualResult: Future[Result] = eventualQueryChains.map {
        queryChains => {
          log.debug(s"issue:$selected queryChains.length= ${queryChains.length}")
          if (queryChains.isEmpty == 0) Ok("OK")
          else {
            Ok(queryChainsToJson(queryChains))
          }
        }
      }
      eventualResult
    }
    result.getOrElse {
      val e: Throwable = result.failed.get
      val sw = new StringWriter
      e.printStackTrace(new PrintWriter(sw))
      log.error(s"Retrieving Query Chain failed ${sw.toString}")
      Future(Ok("Retrieving Query Chain failed"))
    }
  }


  def nextIssueId = Action(parse.multipartFormData) { implicit req =>
    val body: Map[String, Seq[String]] = req.body.dataParts

    val gmc = param(body, "gmc").getOrElse("")

    import scala.concurrent.duration._
    var nextIssueId = ""
    val result: Try[String] = Await.ready(issueTracking.nextIssueId(gmc), 30 seconds).value.get
    result match {
      case scala.util.Success(nextId) => nextIssueId = nextId
      case scala.util.Failure(e) => log.error(e.toString)
    }
    Ok(nextIssueId)
  }

  def save = Action(parse.multipartFormData) { implicit req =>
    Ok(saveIssueHelper.validateAndSave(req.body.dataParts).toString)
  }

  def update = Action(parse.multipartFormData) { implicit req =>
    Ok(saveIssueHelper.validateAndUpdate(req.body.dataParts).toString)
  }
}
