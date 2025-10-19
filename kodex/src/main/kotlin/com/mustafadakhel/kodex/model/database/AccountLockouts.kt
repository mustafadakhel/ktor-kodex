package com.mustafadakhel.kodex.model.database

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.*

internal object AccountLockouts : UUIDTable("account_lockouts") {
    val identifier = varchar("identifier", 255).uniqueIndex()
    val lockedAt = datetime("locked_at").defaultExpression(CurrentDateTime)
    val lockedUntil = datetime("locked_until")
    val reason = varchar("reason", 255)
    val failedAttemptCount = integer("failed_attempt_count")
}

internal class AccountLockoutDao(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<AccountLockoutDao>(AccountLockouts)

    var identifier by AccountLockouts.identifier
    var lockedAt by AccountLockouts.lockedAt
    var lockedUntil by AccountLockouts.lockedUntil
    var reason by AccountLockouts.reason
    var failedAttemptCount by AccountLockouts.failedAttemptCount
}
