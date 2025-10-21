package com.mustafadakhel.kodex.model.database

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.*

internal object Tokens : UUIDTable() {
    val userId = reference("user_id", Users).index()
    val tokenHash = text("token_hash").index()
    val type = varchar("type", 16)
    val revoked = bool("revoked").default(false)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val expiresAt = datetime("expires_at")
    val tokenFamily = uuid("token_family").nullable().index()
    val parentTokenId = uuid("parent_token_id").nullable()
    val firstUsedAt = datetime("first_used_at").nullable()
    val lastUsedAt = datetime("last_used_at").nullable()
}

internal class TokenDao(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<TokenDao>(Tokens)

    var userId by Tokens.userId
    var tokenHash by Tokens.tokenHash
    var type by Tokens.type
    var revoked by Tokens.revoked
    var createdAt by Tokens.createdAt
    var expiresAt by Tokens.expiresAt
    var tokenFamily by Tokens.tokenFamily
    var parentTokenId by Tokens.parentTokenId
    var firstUsedAt by Tokens.firstUsedAt
    var lastUsedAt by Tokens.lastUsedAt
}