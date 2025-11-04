package com.mustafadakhel.kodex.passwordreset.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Database table for password reset tokens.
 *
 * Note: Uses uuid() instead of reference() because Users table is internal to core module.
 * Foreign key CASCADE will be set up via database migration.
 */
internal object PasswordResetTokens : Table("password_reset_tokens") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").index()
    val token = varchar("token", 32).uniqueIndex()
    val contactValue = varchar("contact_value", 255)
    val createdAt = datetime("created_at").index()
    val expiresAt = datetime("expires_at").index()
    val usedAt = datetime("used_at").nullable().index()
    val ipAddress = varchar("ip_address", 45).nullable()

    override val primaryKey = PrimaryKey(id)
}
