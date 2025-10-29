package com.mustafadakhel.kodex.model.database

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.*

internal object FailedLoginAttempts : UUIDTable("failed_login_attempts") {
    val identifier = varchar("identifier", 255).index()
    val ipAddress = varchar("ip_address", 45)
    val userAgent = varchar("user_agent", 500).nullable()
    val attemptedAt = datetime("attempted_at").defaultExpression(CurrentDateTime)
    val reason = varchar("reason", 100)
}

internal class FailedLoginAttemptDao(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<FailedLoginAttemptDao>(FailedLoginAttempts)

    var identifier by FailedLoginAttempts.identifier
    var ipAddress by FailedLoginAttempts.ipAddress
    var userAgent by FailedLoginAttempts.userAgent
    var attemptedAt by FailedLoginAttempts.attemptedAt
    var reason by FailedLoginAttempts.reason
}
