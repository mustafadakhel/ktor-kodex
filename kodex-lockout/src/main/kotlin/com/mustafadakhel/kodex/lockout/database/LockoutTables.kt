package com.mustafadakhel.kodex.lockout.database

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

/**
 * Table for tracking failed login attempts.
 * Tracks attempts by identifier (email/phone), userId (for real accounts), and IP address.
 * Used for both throttling (all attempts) and lockout (real accounts only).
 */
public object FailedLoginAttempts : UUIDTable("failed_login_attempts") {
    public val identifier: Column<String> = varchar("identifier", 255).index()
    public val userId: Column<UUID?> = uuid("user_id").nullable().index()
    public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable().index()
    public val attemptedAt: Column<kotlinx.datetime.LocalDateTime> = datetime("attempted_at").defaultExpression(CurrentDateTime).index()
    public val reason: Column<String> = varchar("reason", 255)
}

/**
 * Table for tracking account lockouts.
 * Separate from Users table to maintain clean architecture - lockout is an optional extension feature.
 * Note: Uses uuid() instead of reference() because Users table is internal to core module.
 * Foreign key CASCADE will be set up via database migration.
 */
public object AccountLocks : UUIDTable("account_locks") {
    public val userId: Column<UUID> = uuid("user_id").uniqueIndex()
    public val lockedUntil: Column<kotlinx.datetime.LocalDateTime?> = datetime("locked_until").nullable()
    public val reason: Column<String> = varchar("reason", 255)
    public val lockedAt: Column<kotlinx.datetime.LocalDateTime> = datetime("locked_at").defaultExpression(CurrentDateTime)
}
