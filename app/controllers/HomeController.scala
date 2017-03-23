package controllers

import java.io.{File, PrintWriter, StringWriter}
import javax.inject._

import controllers.UiUtils._
import dao.Searching.{SearchRequest, SearchResult}
import domain._
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.{JsString, JsObject, JsValue, Json}
import play.api.libs.json.Json._
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import services.{IssueTrackingService, MailService}

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Try, Failure, Success}

@Singleton
class HomeController @Inject()(issueTracking: IssueTrackingService, mailService: MailService)(implicit ec: ExecutionContext) extends Controller {

  val log: Logger = LoggerFactory.getLogger(this.getClass())

  /** Create an Action to render an HTML page with a welcome message.
    * The configuration in the `routes` file means that this method
    * will be called when the application receives a `GET` request with
    * a path of `/`.
    */
  def listIssues = Action.async { implicit req =>
    issueTracking.allIssues.map(issues => Ok(views.html.issues(issues)))
  }

  //TODO : To be removed (Just a temporary method to create a table using model)
  def tmpMethod = Action.async { implicit req =>
    issueTracking.tmpMethod.map(_ =>
      Redirect(routes.HomeController.listIssues))
  }



  def listAjaxAsync = Action.async { implicit req =>

    val searchRequest: SearchRequest = DatatablesRequestBuilder.build(req.queryString)

    val findResult: Future[SearchResult[LoggedIssue]] = issueTracking.findBySearchRequest(searchRequest)

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


  def container = Action {
    Ok(views.html.container())
  }



  def sendNotifications() = Action.async { implicit req =>
    val selected = param(req, "selectedIssues").get
    log.debug(s"selected issues=$selected")
    val issueIds = selected.split(",").toList

    val findResult: Future[SearchResult[LoggedIssue]] = issueTracking.findByIssueIds(issueIds)
    var total: Int = 0
    var selectedIssues: Seq[LoggedIssue] = null

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


  def export = Action.async(parse.tolerantFormUrlEncoded) { implicit req =>

    val searchRequest: SearchRequest = DatatablesRequestBuilder.build(req.queryString)

    val findResult: Future[SearchResult[LoggedIssue]] = issueTracking.findBySearchRequest(searchRequest)

    val csv = new StringBuilder(LoggedIssue.csvHeaderForUI + "\n")

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
        Ok(failuresToJson(failures))
      }
    }
    result.getOrElse {
      val e: Throwable = result.failed.get
      log.error(s"File upload failed ${e.getMessage}\n" + e.getStackTrace.mkString("\n"))
      Ok("File upload failed")
    }
  }



  //toJson without building a 'failure' case class & writes method
  def failuresToJson(failures: List[(Int, Throwable)]): JsValue = {

    val list: List[JsObject] = failures.map { case (i, throwable) =>
      Json.obj("rownum" -> JsString(i.toString),
        "error" -> JsString(throwable.toString))
    }
    Json.toJson(list)
  }

}
