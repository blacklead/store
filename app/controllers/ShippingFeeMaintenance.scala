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
import play.api.Play

object ShippingFeeMaintenance extends Controller with I18nAware with NeedLogin with HasLogger {
  lazy val ShippingToCountries = Play.current.configuration.getStringList("ship.to.countries").get.map {
    CountryCode.byName(_)
  }
  def countryDropDown(implicit lang: Lang) = ShippingToCountries.map {
    c => String.valueOf(c.code()) -> Messages("country." + c.name)
  }
  val LocationCodeTable = Map(
    CountryCode.JPN -> Address.JapanPrefecturesIntSeq
  )

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
          "historyId" -> longNumber,
          "taxId" -> longNumber,
          "fee" -> bigDecimal.verifying(min(BigDecimal(0))),
          "validUntil" -> jodaDate("yyyy-MM-dd HH:mm:ss")
        ) (ChangeFeeHistory.apply)(ChangeFeeHistory.unapply)
      )
    ) (ChangeFeeHistoryTable.apply)(ChangeFeeHistoryTable.unapply)
  )

  val addFeeHistoryForm = Form(
    mapping(
      "historyId" -> ignored(0L),
      "taxId" -> longNumber,
      "fee" -> bigDecimal.verifying(min(BigDecimal(0))),
      "validUntil" -> jodaDate("yyyy-MM-dd HH:mm:ss")
    ) (ChangeFeeHistory.apply)(ChangeFeeHistory.unapply)
  )

  val createShippingFeeForm = Form(
    mapping(
      "feeId" -> longNumber,
      "countryCode" -> number,
      "locationCodeTable" -> list(number)
    ) (CreateShippingFee.apply)(CreateShippingFee.unapply)
  )

  val removeHistoryForm = Form(
    "historyId" -> longNumber
  )

  val removeFeeForm = Form(
    "feeId" -> longNumber
  )

  val createFeeForm = Form(
    tuple(
      "boxId" -> longNumber,
      "countryCode" -> longNumber
    )
  )

  def createFeeHistoryForm(feeId: Long): Form[ChangeFeeHistoryTable] = {
    DB.withConnection { implicit conn => {
      val histories = ShippingFeeHistory.list(feeId).map {
        h => ChangeFeeHistory(h.id.get, h.taxId, h.fee, new DateTime(h.validUntil))
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
            views.html.admin.shippingFeeMaintenance(
              formWithErrors, None, List(), createFeeForm, countryDropDown
            )
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
          Ok(
            views.html.admin.shippingFeeMaintenance(
              feeMaintenanceForm.fill(FeeMaintenance(boxId, new DateTime(now))),
              Some(rec),
              list,
              createFeeForm,
              countryDropDown
            )
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

      Ok(
        views.html.admin.shippingFeeHistoryMaintenance(
          box, fee, form, addFeeHistoryForm, Tax.tableForDropDown
        )
      )
    }
  }}

  def changeHistory(feeId: Long) = isAuthenticated { implicit login => forSuperUser { implicit request =>
    DB.withTransaction { implicit conn =>
      val fee = ShippingFee(feeId)

      changeFeeHistoryForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ShippingFeeMaintenance.changeHistory." + formWithErrors + ".")
          BadRequest(
            views.html.admin.shippingFeeHistoryMaintenance(
              ShippingBox(fee.shippingBoxId),
              fee,
              formWithErrors, addFeeHistoryForm, Tax.tableForDropDown
            )
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

  def addHistory(feeId: Long) = isAuthenticated { implicit login => forSuperUser { implicit request =>
    addFeeHistoryForm.bindFromRequest.fold(
      formWithErrors => {
        logger.error("Validation error in ShippingFeeMaintenance.addHistory." + formWithErrors + ".")
        DB.withConnection { implicit conn =>
          val fee = ShippingFee(feeId)

          BadRequest(
            views.html.admin.shippingFeeHistoryMaintenance(
              ShippingBox(fee.shippingBoxId),
              fee,
              createFeeHistoryForm(feeId), formWithErrors, Tax.tableForDropDown
            )
          )
        }
      },
      newHistory => {
        DB.withTransaction { implicit conn =>
          newHistory.add(feeId)
        }
        Redirect(
          routes.ShippingFeeMaintenance.editHistory(feeId)
        ).flashing("message" -> Messages("shippingFeeHistoryAdded"))
      }
    )
  }}

  def removeHistory = isAuthenticated { implicit login => forSuperUser { implicit request =>
    val historyId = removeHistoryForm.bindFromRequest.get
    DB.withConnection { implicit conn =>
      val his = ShippingFeeHistory(historyId)
      ShippingFeeHistory.remove(historyId)

      Redirect(
        routes.ShippingFeeMaintenance.editHistory(his.shippingFeeId)
      ).flashing("message" -> Messages("shippingFeeHistoryRemoved"))
    }
  }}

  def startCreateShippingFee = isAuthenticated { implicit login => forSuperUser { implicit request =>
    val (boxId, cc) = createFeeForm.bindFromRequest.get
    val countryCode = CountryCode.byIndex(cc.toInt)
    val existingLocations = DB.withConnection { implicit conn =>
      ShippingFee.list(boxId, countryCode).map { fee =>
        fee.locationCode
      }.toSet
    }
    
    Ok(
      views.html.admin.createShippingFee(
        boxId,
        createShippingFeeForm.fill(
          CreateShippingFee(
            boxId.toLong, countryCode.code(), existingLocations.toList
          )
        ),
        existingLocations,
        LocationCodeTable(countryCode)
      )
    )
  }}

  def createShippingFee(boxId: Long) = isAuthenticated { implicit login => forSuperUser { implicit request =>
    createShippingFeeForm.bindFromRequest.fold(
      formWithErrors => {
        logger.error("Validation error in ShippingFeeMaintenance.createShippingFeeForm." + formWithErrors + ".")
        Redirect(
          routes.ShippingFeeMaintenance.startFeeMaintenance()
        )
      },
      newFee => {
        DB.withConnection { implicit conn =>
          newFee.update(boxId)
          Redirect(
            routes.ShippingFeeMaintenance.startFeeMaintenanceNow(boxId)
          ).flashing("message" -> Messages("shippingFeeIsUpdated"))
        }
      }
    )
  }}

  def removeFee(boxId: Long) = isAuthenticated { implicit login => forSuperUser { implicit request =>
    val feeId = removeFeeForm.bindFromRequest.get
    DB.withTransaction { implicit conn =>
      ShippingFee.removeWithHistories(feeId)
      Redirect(
        routes.ShippingFeeMaintenance.startFeeMaintenanceNow(boxId)
      ).flashing("message" -> Messages("shippingFeeIsRemoved"))
    }
  }}
}
