package com.mustafadakhel.kodex.lockout.database

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Table for tracking failed login attempts.
 * Used to detect brute force attacks and trigger account lockouts.
 */
public object FailedLoginAttempts : UUIDTable("failed_login_attempts") {
    public val identifier: Column<String> = varchar("identifier", 255).index()
    public val attemptedAt: Column<kotlinx.datetime.LocalDateTime> = datetime("attempted_at").defaultExpression(CurrentDateTime).index()
    public val reason: Column<String> = varchar("reason", 255)
}

/**
 * Table for storing active account lockouts.
 * When a user exceeds the maximum failed attempts, their account is locked.
 */
public object AccountLockouts : UUIDTable("account_lockouts") {
    public val identifier: Column<String> = varchar("identifier", 255).uniqueIndex()
    public val lockedAt: Column<kotlinx.datetime.LocalDateTime> = datetime("locked_at").defaultExpression(CurrentDateTime)
    public val lockedUntil: Column<kotlinx.datetime.LocalDateTime> = datetime("locked_until")
    public val reason: Column<String> = varchar("reason", 255)
    public val failedAttemptCount: Column<Int> = integer("failed_attempt_count")
}
