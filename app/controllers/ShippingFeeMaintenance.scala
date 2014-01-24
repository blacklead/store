package controllers

import play.api.i18n.Lang
import play.api.data.Form
import scala.collection.JavaConversions._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.mvc.Result
import play.api.mvc.Request
import play.api.mvc.AnyContent
import models._
import play.api.data.Form
import controllers.I18n.I18nAware
import play.api.mvc.Controller
import play.api.db.DB
import play.api.i18n.Messages
import play.api.Play.current
import models.CreateShippingBox
import org.joda.time.DateTime

object ShippingFeeMaintenance extends Controller with I18nAware with NeedLogin with HasLogger {
  val createShippingBoxForm = Form(
    mapping(
      "siteId" -> longNumber,
      "itemClass" -> longNumber,
      "boxSize" -> number,
      "boxName" -> text.verifying(nonEmpty, maxLength(32))
    ) (CreateShippingBox.apply)(CreateShippingBox.unapply)
  )

  def feeMaintenanceForm(implicit lang: Lang) = Form(
    mapping(
      "boxId" -> longNumber,
      "now" -> jodaDate(Messages("shipping.fee.maintenance.date.format"))
    )(FeeMaintenance.apply)(FeeMaintenance.unapply)
  )

  val changeFeeHistoryForm = Form(
    mapping(
      "histories" -> seq(
        mapping(
          "taxId" -> longNumber,
          "fee" -> bigDecimal.verifying(min(BigDecimal(0))),
          "validUntil" -> jodaDate("yyyy-MM-dd HH:mm:ss")
        ) (ChangeFeeHistory.apply)(ChangeFeeHistory.unapply)
      )
    ) (ChangeFeeHistoryTable.apply)(ChangeFeeHistoryTable.unapply)
  )

  def createFeeHistoryForm(feeId: Long): Form[ChangeFeeHistoryTable] = {
    DB.withConnection { implicit conn => {
      val histories = ShippingFeeHistory.list(feeId).map {
        h => ChangeFeeHistory(h.taxId, h.fee, new DateTime(h.validUntil))
      }.toSeq

      changeFeeHistoryForm.fill(ChangeFeeHistoryTable(histories))
    }}
  }

  def startFeeMaintenanceNow(boxId: Long) = isAuthenticated { implicit login =>
    forSuperUser { implicit request =>
      feeMaintenance(boxId, System.currentTimeMillis)
    }
  }

  def startFeeMaintenance = isAuthenticated { implicit login =>
    forSuperUser { implicit request =>
      feeMaintenanceForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ShippingFeeMaintenance.startFeeMaintenance.")
          BadRequest(
            views.html.admin.shippingFeeMaintenance(formWithErrors, None, List())
          )
        },
        newDate => feeMaintenance(newDate.boxId, newDate.now.getMillis)
      )
    }
  }

  private def feeMaintenance(boxId: Long, now: Long)(
    implicit req: Request[AnyContent],
    loginSession: LoginSession
  ): Result = {
    DB.withConnection { implicit conn =>
      ShippingBox.getWithSite(boxId) match {
        case Some(rec) => {
          val list = ShippingFee.listWithHistory(boxId, now)
          Ok(views.html.admin.shippingFeeMaintenance(
            feeMaintenanceForm.fill(FeeMaintenance(boxId, new DateTime(now))), Some(rec), list)
          )
        }
        case None =>
          Redirect(
            routes.ShippingBoxMaintenance.editShippingBox()
          ).flashing("message" -> Messages("record.already.deleted"))
      }
    }
  }

  def editHistory(feeId: Long) = isAuthenticated { implicit login => forSuperUser { implicit request =>
    DB.withConnection { implicit conn =>
      val fee = ShippingFee(feeId)
      val box = ShippingBox(fee.shippingBoxId)
      val form = createFeeHistoryForm(feeId)

      Ok(views.html.admin.shippingFeeHistoryMaintenance(box, fee, form, Tax.tableForDropDown))
    }
  }}

  def changeHistory(feeId: Long) = isAuthenticated { implicit login => forSuperUser { implicit request =>
    DB.withConnection { implicit conn =>
      val fee = ShippingFee(feeId)
      val box = ShippingBox(fee.shippingBoxId)

      changeFeeHistoryForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ShippingFeeMaintenance.changeHistory." + formWithErrors + ".")
          BadRequest(
            views.html.admin.shippingFeeHistoryMaintenance(box, fee, formWithErrors, Tax.tableForDropDown)
          )
        },
        newHistories => {
          newHistories.update(feeId)
          Redirect(
            routes.ShippingFeeMaintenance.editHistory(feeId)
          ).flashing("message" -> Messages("shippingFeeHistoryUpdated"))
        }            
      )
    }
  }}
}
