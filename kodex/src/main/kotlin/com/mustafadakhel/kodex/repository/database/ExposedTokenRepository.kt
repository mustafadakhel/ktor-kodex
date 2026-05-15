package com.mustafadakhel.kodex.repository.database

import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.database.PersistedToken
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.schema.KodexDatabase
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

internal fun databaseTokenRepository(db: KodexDatabase, realmId: String): TokenRepository =
    ExposedTokenRepository(db, realmId)

internal class ExposedTokenRepository(
    private val db: KodexDatabase,
    private val realmId: String,
) : TokenRepository {

    private val tokens get() = db.core.tokens

    override fun storeToken(token: PersistedToken): UUID {
        require(token.realmId == realmId) {
            "Token realm '${token.realmId}' does not match repository realm '$realmId'"
        }
        return db.transaction {
            tokens.insertAndGetId {
                it[tokens.id] = token.id
                it[tokens.userId] = token.userId
                it[tokens.type] = token.type.name
                it[tokens.revoked] = token.revoked
                it[tokens.tokenHash] = token.tokenHash
                it[tokens.expiresAt] = token.expiresAt
                it[tokens.tokenFamily] = token.tokenFamily
                it[tokens.parentTokenId] = token.parentTokenId
                it[tokens.firstUsedAt] = token.firstUsedAt
                it[tokens.lastUsedAt] = token.lastUsedAt
                it[tokens.realmId] = token.realmId
            }
        }.value
    }

    override fun revokeToken(tokenHash: String): Unit = db.transaction {
        tokens.update({ (tokens.tokenHash eq tokenHash) and (tokens.realmId eq realmId) }) {
            it[tokens.revoked] = true
        }
        Unit
    }

    override fun revokeToken(tokenId: UUID): Unit = db.transaction {
        tokens.update({ (tokens.id eq tokenId) and (tokens.realmId eq realmId) }) {
            it[tokens.revoked] = true
        }
        Unit
    }

    override fun findToken(tokenId: UUID): PersistedToken? = db.transaction {
        tokens.selectAll()
            .where { (tokens.id eq tokenId) and (tokens.realmId eq realmId) }
            .singleOrNull()
            ?.toPersistedToken()
    }

    override fun deleteToken(tokenId: UUID): Unit = db.transaction {
        val deleted = tokens.deleteWhere {
            (tokens.id eq tokenId) and (tokens.realmId eq realmId)
        }
        if (deleted == 0) throw NoSuchElementException("Token with id $tokenId not found")
    }

    override fun deleteToken(tokenHash: String): Unit = db.transaction {
        tokens.deleteWhere {
            (tokens.tokenHash eq tokenHash) and (tokens.realmId eq realmId)
        }
        Unit
    }

    override fun revokeTokens(userId: UUID): Unit = db.transaction {
        tokens.update({ (tokens.userId eq userId) and (tokens.realmId eq realmId) }) {
            it[tokens.revoked] = true
        }
        Unit
    }

    override fun markTokenAsUsedIfUnused(tokenId: UUID, now: LocalDateTime): Boolean = db.transaction {
        val updated = tokens.update({
            (tokens.id eq tokenId) and (tokens.realmId eq realmId) and tokens.firstUsedAt.isNull()
        }) {
            it[tokens.firstUsedAt] = now
            it[tokens.lastUsedAt] = now
        }
        updated > 0
    }

    override fun findTokenByHash(tokenHash: String): PersistedToken? = db.transaction {
        tokens.selectAll()
            .where { (tokens.tokenHash eq tokenHash) and (tokens.realmId eq realmId) }
            .singleOrNull()
            ?.toPersistedToken()
    }

    override fun revokeTokenFamily(tokenFamily: UUID): Unit = db.transaction {
        tokens.selectAll()
            .where { (tokens.tokenFamily eq tokenFamily) and (tokens.realmId eq realmId) }
            .forUpdate()
            .toList()

        tokens.update({ (tokens.tokenFamily eq tokenFamily) and (tokens.realmId eq realmId) }) {
            it[tokens.revoked] = true
        }
        Unit
    }

    override fun findTokensByFamily(tokenFamily: UUID): List<PersistedToken> = db.transaction {
        tokens.selectAll()
            .where { (tokens.tokenFamily eq tokenFamily) and (tokens.realmId eq realmId) }
            .map { it.toPersistedToken() }
    }

    private fun ResultRow.toPersistedToken() = PersistedToken(
        id = this[tokens.id].value,
        userId = this[tokens.userId].value,
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
