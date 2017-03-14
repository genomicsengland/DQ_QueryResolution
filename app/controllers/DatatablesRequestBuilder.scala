package controllers

import java.util.Date

import controllers.UiUtils._
import dao.Searching.SearchRequest
import domain.{Open, SearchCriteria}
import org.joda.time.LocalDate
import org.slf4j.{Logger, LoggerFactory}
import play.api.mvc.{AnyContent, Request}

/**
  * This builder is specifically to service the requirements of the UI Datatables.js component
  */
object DatatablesRequestBuilder {

  val log: Logger = LoggerFactory.getLogger(this.getClass())

  //needs to be kept in sync with ui
  private val uiColumnNames: List[String] = List("select/checkbox", "status", "DT_RowId", "loggedBy", "dateLogged", "issueOrigin", "GMC", "description", "patientId")

  def build(request: Request[AnyContent]): SearchRequest = {
    //for security coerce these to int and provide safe fallbacks
    val draw = request.getQueryString("draw").getOrElse("1").toInt
    val offset = request.getQueryString("start").getOrElse("0").toInt
    val pageSize = request.getQueryString("length").getOrElse("10").toInt

    val filter = param(request, "filter")
    val search = param(request, "search[value]")
    var isNew = false
    var isSearch = false

    filter match {
      case Some(s) if s.equalsIgnoreCase("new") => {
        isNew = true
        log.info("new issues selected")
      }
      case Some(s) => log.info("filter value?= " + s)
      case None => log.info("no filter param,check others")
    }

    search match {
      case Some(s) if s.length > 0 => {
        isSearch = true
        log.info(s"search for $s")
      }
      case Some(s) => log.info("search value?= " + s)
      case None => log.info("no search param,check others")
    }

    var gmc = param(request, "gmc")
    var dateLogged: Option[Date] = None
    var patientId: Option[String] = None
    var issueStatus: Option[domain.Status] = None

    if (isNew) {
      patientId = None
      gmc = None
      issueStatus = Some(Open)
      val days = param(request, "days").getOrElse("0").toInt
      dateLogged = Some(LocalDate.now().minusDays(days).toDate)
    }

    //search is higher precedence than filters
    if (isSearch) {
      patientId = Some(search.get)
      gmc = None
      dateLogged = None
      issueStatus = None
    }

    val sortCol = param(request, "order[0][column]")
    val sortDir = param(request, "order[0][dir]")

    //default to sort by dateLogged/desc
    val sortColFromUI = uiColumnNames(sortCol.getOrElse("3").toInt)
    val sortOrderFromUI = sortDir.getOrElse("desc")
println(s"sortCol $sortCol sortDir $sortDir sortColFromUI $sortColFromUI  sortOrderFromUI $sortOrderFromUI")

    //this was the mechanism to support multiple sort cols, leaving here for the moment
//    val sortFields: Option[List[String]] = Some(List(sortColFromUI))
//    val sortDirections: Option[List[String]] = Some(List(sortOrderFromUI))
    val sortCriteria : Option[(String, String)] = Some((sortColFromUI,sortOrderFromUI))
    val searchCriteria = SearchCriteria(gmc, issueStatus = issueStatus, dateLogged = dateLogged, patientId = patientId)

    val searchRequest: SearchRequest = SearchRequest(offset, pageSize, searchCriteria, draw, sortCriteria)
    log.info(s"searchRequest: $searchRequest")
    searchRequest
  }

}