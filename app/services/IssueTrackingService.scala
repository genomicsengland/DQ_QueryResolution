package services

import java.time.{LocalDate, ZoneId}
import java.util.Date
import javax.inject.{Inject, Singleton}

import dao.IssueTrackingDao
import domain._

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

trait IssueTrackingService {
  def allIssues: Future[Seq[LoggedIssue]]
  def allIssuesNow: List[LoggedIssue]
  //TODO : To be removed (temporary method to create a table and populate data)
  def tmpMethod: Future[Unit]
}

@Singleton
class IssueTrackingServiceImpl @Inject()(issueTrackingDao: IssueTrackingDao)(implicit ec: ExecutionContext) extends IssueTrackingService {

  def allIssues: Future[List[LoggedIssue]] = {
    Future {
      var issues: List[LoggedIssue] = findAllIssues
      for(x <- 1 to 5){
        issues = issues ::: issues
      }
      issues
    }
  }

  def allIssuesNow: List[LoggedIssue] = {
    var issues: List[LoggedIssue] = findAllIssues
    for(x <- 1 to 5){
      issues = issues ::: issues
    }
    issues
  }

  def findAllIssues: List[LoggedIssue] = {
    tmpPopulateIssues
  }

  //TODO : To be removed (temporary method to provide a handler to the controller for creating a table using sample model)
  def tmpMethod = Future {
    issueTrackingDao.tableSetup(tmpPopulateIssues.toSeq)
  }

  //TODO : To be removed
  private def tmpPopulateIssues = {
    import java.time.temporal.ChronoUnit.DAYS

    import scala.util.Random

    val r = new Random
    val statuses = Status.validStatuses
    val gmcList = List("RGT", "RJ1", "RRK", "RTD", "RP4", "REP", "RW3", "RTD", "RP4")
    val issueOrigin = List("ServiceDesk", "DataQuality", "RedTeam", "Informatics", "BioInformatics")
    val loggedBy = List("RJ", "JS", "DA")

    def randomDateBetween(from: LocalDate, to: LocalDate) = {
      val d = from.plusDays(r.nextInt(DAYS.between(from, to).toInt))
      Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant())
    }

    val data = LoggedIssue(
      1,
      "RYJ-Orp-031",
      statuses(r.nextInt(statuses.length)),
      "RJ",
      randomDateBetween(LocalDate.of(2017, 1, 1), LocalDate.of(2017, 3, 1)),
      "DataQuality",
      "RYJ",
      None,
      "110120123",
      None,
      Some("Group size"),
      "Issue with family group size: Please see 'Group Issues' tab for more details and potential resolutions",
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      Some("Comments on issue")
    )

    val issueLs = ListBuffer(data)

    data.copy(status = statuses(r.nextInt(statuses.length)))
    (1 to 5000).foreach { x =>
      val c = data.copy(
        issueId = "RYJ-Orp-031" + r.nextInt(500),
        loggedBy = loggedBy(r.nextInt(loggedBy.length)),
        status = statuses(r.nextInt(statuses.length)),
        GMC = gmcList(r.nextInt(gmcList.length)),
        dateLogged = randomDateBetween(LocalDate.of(2016, 12, 1), LocalDate.of(2017, 2, 1)),
        issueOrigin = issueOrigin(r.nextInt(issueOrigin.length)),
        dateSent = Some(randomDateBetween(LocalDate.of(2017, 2, 1), LocalDate.of(2017, 3, 1))
        ))
      issueLs += c
    }
    issueLs.toList
  }
}
