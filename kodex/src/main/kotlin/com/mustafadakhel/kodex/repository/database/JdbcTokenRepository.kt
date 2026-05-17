@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.repository.database

import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.Row
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.isNull
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.database.PersistedToken
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.schema.KodexDatabase
import kotlinx.datetime.LocalDateTime
import java.util.UUID

internal fun databaseTokenRepository(db: KodexDatabase, realmId: String): TokenRepository =
    JdbcTokenRepository(db, realmId)

internal class JdbcTokenRepository(
    private val db: KodexDatabase,
    private val realmId: String,
) : TokenRepository {

    private val tokens get() = db.core.tokens

    override fun storeToken(token: PersistedToken): UUID {
        require(token.realmId == realmId) {
            "Token realm '${token.realmId}' does not match repository realm '$realmId'"
        }
        return db.transaction {
            insertReturningKey(tokens, tokens.id) {
                this[tokens.id] = token.id
                this[tokens.userId] = token.userId
                this[tokens.type] = token.type.name
                this[tokens.revoked] = token.revoked
                this[tokens.tokenHash] = token.tokenHash
                this[tokens.expiresAt] = token.expiresAt
                this[tokens.tokenFamily] = token.tokenFamily
                this[tokens.parentTokenId] = token.parentTokenId
                this[tokens.firstUsedAt] = token.firstUsedAt
                this[tokens.lastUsedAt] = token.lastUsedAt
                this[tokens.realmId] = token.realmId
            }
        }
    }

    override fun revokeToken(tokenHash: String): Unit = db.transaction {
        update(tokens) {
            this[tokens.revoked] = true
            where { (tokens.tokenHash eq tokenHash) and (tokens.realmId eq realmId) }
        }
        Unit
    }

    override fun revokeToken(tokenId: UUID): Unit = db.transaction {
        update(tokens) {
            this[tokens.revoked] = true
            where { (tokens.id eq tokenId) and (tokens.realmId eq realmId) }
        }
        Unit
    }

    override fun findToken(tokenId: UUID): PersistedToken? = db.transaction {
        select(tokens)
            .where { (tokens.id eq tokenId) and (tokens.realmId eq realmId) }
            .singleOrNull { it.toPersistedToken() }
    }

    override fun deleteToken(tokenId: UUID): Unit = db.transaction {
        deleteFrom(tokens)
            .where { (tokens.id eq tokenId) and (tokens.realmId eq realmId) }
            .execute()
    }

    override fun deleteToken(tokenHash: String): Unit = db.transaction {
        deleteFrom(tokens)
            .where { (tokens.tokenHash eq tokenHash) and (tokens.realmId eq realmId) }
            .execute()
        Unit
    }

    override fun revokeTokens(userId: UUID): Unit = db.transaction {
        update(tokens) {
            this[tokens.revoked] = true
            where { (tokens.userId eq userId) and (tokens.realmId eq realmId) }
        }
        Unit
    }

    override fun markTokenAsUsedIfUnused(tokenId: UUID, now: LocalDateTime): Boolean = db.transaction {
        val updated = update(tokens) {
            this[tokens.firstUsedAt] = now
            this[tokens.lastUsedAt] = now
            where { (tokens.id eq tokenId) and (tokens.realmId eq realmId) and tokens.firstUsedAt.isNull() }
        }
        updated > 0
    }

    override fun findTokenByHash(tokenHash: String): PersistedToken? = db.transaction {
        select(tokens)
            .where { (tokens.tokenHash eq tokenHash) and (tokens.realmId eq realmId) }
            .singleOrNull { it.toPersistedToken() }
    }

    override fun revokeTokenFamily(tokenFamily: UUID): Unit = db.transaction {
        select(tokens)
            .where { (tokens.tokenFamily eq tokenFamily) and (tokens.realmId eq realmId) }
            .forUpdate()
            .map { }

        update(tokens) {
            this[tokens.revoked] = true
            where { (tokens.tokenFamily eq tokenFamily) and (tokens.realmId eq realmId) }
        }
        Unit
    }

    override fun findTokensByFamily(tokenFamily: UUID): List<PersistedToken> = db.transaction {
        select(tokens)
            .where { (tokens.tokenFamily eq tokenFamily) and (tokens.realmId eq realmId) }
            .map { it.toPersistedToken() }
    }

    override fun consumeAndDeleteToken(tokenId: UUID, userId: UUID): PersistedToken? = db.transaction {
        val token = select(tokens)
            .where { (tokens.id eq tokenId) and (tokens.realmId eq realmId) }
            .forUpdate()
            .singleOrNull { it.toPersistedToken() }
            ?: return@transaction null

        if (token.userId != userId || token.revoked) return@transaction null

        deleteFrom(tokens)
            .where { (tokens.id eq tokenId) and (tokens.realmId eq realmId) }
            .execute()

        token
    }

    override fun consumeAndRevokeToken(tokenId: UUID, userId: UUID, now: LocalDateTime): PersistedToken? =
        db.transaction {
            val token = select(tokens)
                .where { (tokens.id eq tokenId) and (tokens.realmId eq realmId) }
                .forUpdate()
                .singleOrNull { it.toPersistedToken() }
                ?: return@transaction null

            if (token.userId != userId || token.revoked) return@transaction null

            update(tokens) {
                this[tokens.revoked] = true
                if (token.firstUsedAt == null) {
                    this[tokens.firstUsedAt] = now
                    this[tokens.lastUsedAt] = now
                }
                where { (tokens.id eq tokenId) and (tokens.realmId eq realmId) }
            }

            token.copy(
                revoked = true,
                firstUsedAt = token.firstUsedAt ?: now,
                lastUsedAt = token.lastUsedAt ?: now,
            )
        }

    private fun Row.toPersistedToken() = PersistedToken(
        id = this[tokens.id],
        userId = this[tokens.userId],
        tokenHash = this[tokens.tokenHash],
        type = TokenType.fromString(this[tokens.type]),
        revoked = this[tokens.revoked],
        createdAt = this[tokens.createdAt],
        expiresAt = this[tokens.expiresAt],
        realmId = this[tokens.realmId],
        tokenFamily = this[tokens.tokenFamily],
        parentTokenId = this[tokens.parentTokenId],
        firstUsedAt = this[tokens.firstUsedAt],
        lastUsedAt = this[tokens.lastUsedAt],
    )
}
