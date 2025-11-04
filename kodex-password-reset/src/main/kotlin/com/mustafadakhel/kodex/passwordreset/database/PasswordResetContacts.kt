package com.mustafadakhel.kodex.passwordreset.database

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

/**
 * Table for tracking contact information for password reset.
 * Separate from Users table to maintain clean architecture - password reset is an optional extension feature.
 *
 * Note: Uses uuid() instead of reference() because Users table is internal to core module.
 * Foreign key CASCADE will be set up via database migration.
 */
internal object PasswordResetContacts : Table("password_reset_contacts") {
    val userId: Column<UUID> = uuid("user_id").index()
    val contactType: Column<String> = varchar("contact_type", 50)
    val contactValue: Column<String> = varchar("contact_value", 255).index()
    val createdAt: Column<LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt: Column<LocalDateTime> = datetime("updated_at").defaultExpression(CurrentDateTime)

    init {
        uniqueIndex(userId, contactType)
    }
}
