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
    val realmId = varchar("realm_id", 50)
    val userId = uuid("user_id")
    val token = varchar("token", 32).uniqueIndex()
    val contactValue = varchar("contact_value", 255)
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")
    val usedAt = datetime("used_at").nullable()
    val ipAddress = varchar("ip_address", 45).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, realmId)
        index(false, userId)
        index(false, realmId, expiresAt)
        index(false, createdAt)
        index(false, usedAt)
    }
}
