package models

import play.api.db.DB
import play.api.Play.current
import java.sql.Connection

trait HasYearMonth {
  val year: Int
  val month: Int
  def next: HasYearMonth
}

case class YearMonth(year: Int, month: Int) extends HasYearMonth {
  def next: YearMonth = if (month < 12) YearMonth(year, month + 1) else YearMonth(year + 1, 1)
}

case class YearMonthSite(year: Int, month: Int, siteId: Long) extends HasYearMonth {
  def next: YearMonthSite = 
    if (month < 12) YearMonthSite(year, month + 1, siteId) else YearMonthSite(year + 1, 1, siteId)
}

case class YearMonthUser(year: Int, month: Int, userId: Long) extends HasYearMonth {
  def next: YearMonthUser = 
    if (month < 12) YearMonthUser(year, month + 1, userId) else YearMonthUser(year + 1, 1, userId)
  lazy val userIdOpt: Option[Long] = if (userId == 0) None else Some(userId)
}

object YearMonth {
  val MinYear = 1900
  val MaxYear = 9999
}
