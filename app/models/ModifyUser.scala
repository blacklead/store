package models

import helpers.{PasswordHash, TokenGenerator}
import java.security.MessageDigest
import java.sql.Connection

case class ModifyUser(
  userId: Long, userName: String, firstName: String, middleName: Option[String], lastName: String,
  email: String, password: String, companyName: String, sendNoticeMail: Boolean
) extends CreateUserBase with NotNull {
  def update(implicit tokenGenerator: TokenGenerator, conn: Connection) {
    val salt = tokenGenerator.next
    val hash = PasswordHash.generate(password, salt)
    StoreUser.update(
      userId, userName, firstName, middleName, lastName, email, hash, salt, companyName
    )

    OrderNotification.delete(userId)
    if (sendNoticeMail)
      OrderNotification.createNew(userId)
  }
}

object ModifyUser {
  def apply(user: ListUserEntry): ModifyUser = ModifyUser(
    user.user.id.get,
    user.user.userName,
    user.user.firstName,
    user.user.middleName,
    user.user.lastName,
    user.user.email,
    "",
    user.user.companyName.getOrElse(""),
    user.sendNoticeMail
  )

  def fromForm(
    userId: Long, userName: String, firstName: String, middleName: Option[String], lastName: String,
    email: String, passwords: (String, String), companyName: String, sendNoticeMail: Boolean
  ): ModifyUser =
    ModifyUser(userId, userName, firstName, middleName, lastName, email, passwords._1, companyName, sendNoticeMail)

  def toForm(m: ModifyUser) = Some(
    m.userId, m.userName, m.firstName, m.middleName, m.lastName, m.email, (m.password, m.password), m.companyName, m.sendNoticeMail
  )
}
