package models

import helpers.{PasswordHash, TokenGenerator}
import java.sql.Connection

trait CreateUser extends CreateUserBase {
  val role: UserRole

  def save(implicit tokenGenerator: TokenGenerator, conn: Connection): StoreUser = {
    val salt = tokenGenerator.next
    val hash = PasswordHash.generate(password, salt)
    StoreUser.create(userName, firstName, middleName, lastName, email, hash, salt, role, Some(companyName))
  }
}

trait CreateUserObject {
  def toForm(m: CreateUser) = Some(
    m.userName, m.firstName, m.middleName, m.lastName, m.email, (m.password, m.password), m.companyName
  )
}
