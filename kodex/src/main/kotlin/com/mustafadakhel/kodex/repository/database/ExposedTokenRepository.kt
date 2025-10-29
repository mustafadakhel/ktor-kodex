package com.mustafadakhel.kodex.repository.database

import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.database.PersistedToken
import com.mustafadakhel.kodex.model.database.TokenDao
import com.mustafadakhel.kodex.model.database.Tokens
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.util.exposedTransaction
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import java.util.*

internal fun databaseTokenRepository(): TokenRepository = ExposedTokenRepository

private object ExposedTokenRepository : TokenRepository {
    override fun storeToken(token: PersistedToken): UUID = exposedTransaction {
        Tokens.insertAndGetId {
            it[Tokens.id] = token.id
            it[Tokens.userId] = token.userId
            it[Tokens.type] = token.type.name
            it[Tokens.revoked] = token.revoked
            it[Tokens.tokenHash] = token.tokenHash
            it[Tokens.expiresAt] = token.expiresAt
            it[Tokens.tokenFamily] = token.tokenFamily
            it[Tokens.parentTokenId] = token.parentTokenId
            it[Tokens.firstUsedAt] = token.firstUsedAt
            it[Tokens.lastUsedAt] = token.lastUsedAt
        }
    }.value

    override fun revokeToken(tokenHash: String): Unit = exposedTransaction {
        Tokens.update({ Tokens.tokenHash eq tokenHash }) {
            it[Tokens.revoked] = true
        }
        Unit
    }

    override fun revokeToken(tokenId: UUID): Unit = exposedTransaction {
        Tokens.update({ Tokens.id eq tokenId }) {
            it[Tokens.revoked] = true
        }
        Unit
    }

    override fun findToken(tokenId: UUID): PersistedToken? = exposedTransaction {
        TokenDao.findById(tokenId)?.toEntity()
    }

    override fun deleteToken(tokenId: UUID) = exposedTransaction {
        TokenDao.findById(tokenId)?.delete() ?: throw NoSuchElementException("Token with id $tokenId not found")
    }

    override fun deleteToken(tokenHash: String): Unit = exposedTransaction {
        Tokens.deleteWhere { Tokens.tokenHash eq tokenHash }
        Unit
    }

    override fun revokeTokens(userId: UUID): Unit = exposedTransaction {
        Tokens.update({ Tokens.userId eq userId }) {
            it[Tokens.revoked] = true
        }
        Unit
    }

    override fun markTokenAsUsedIfUnused(tokenId: UUID, now: kotlinx.datetime.LocalDateTime): Boolean = exposedTransaction {
        val updated = Tokens.update({
            (Tokens.id eq tokenId) and Tokens.firstUsedAt.isNull()
        }) {
            it[Tokens.firstUsedAt] = now
            it[Tokens.lastUsedAt] = now
        }
        updated > 0
    }

    override fun findTokenByHash(tokenHash: String): PersistedToken? = exposedTransaction {
        TokenDao.find { Tokens.tokenHash eq tokenHash }
            .singleOrNull()
            ?.toEntity()
    }

    override fun revokeTokenFamily(tokenFamily: UUID): Unit = exposedTransaction {
        Tokens.update({ Tokens.tokenFamily eq tokenFamily }) {
            it[Tokens.revoked] = true
        }
        Unit
    }

    override fun findTokensByFamily(tokenFamily: UUID): List<PersistedToken> = exposedTransaction {
        TokenDao.find { Tokens.tokenFamily eq tokenFamily }
            .map { it.toEntity() }
    }

    private fun TokenDao.toEntity() = PersistedToken(
        id = id.value,
        userId = userId.value,
        tokenHash = tokenHash,
        type = TokenType.fromString(type),
        revoked = revoked,
        createdAt = createdAt,
        expiresAt = expiresAt,
        tokenFamily = tokenFamily,
        parentTokenId = parentTokenId,
        firstUsedAt = firstUsedAt,
        lastUsedAt = lastUsedAt,
    )
}
