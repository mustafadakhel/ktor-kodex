package com.mustafadakhel.kodex.repository.database

import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.database.PersistedToken
import com.mustafadakhel.kodex.model.database.TokenDao
import com.mustafadakhel.kodex.model.database.Tokens
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.util.exposedTransaction
import org.jetbrains.exposed.sql.insertAndGetId
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
            it[Tokens.usedAt] = token.usedAt
        }
    }.value

    override fun revokeToken(tokenHash: String) {
        TokenDao.find { Tokens.tokenHash eq tokenHash }
            .forEach { it.revoked = true }
    }

    override fun findToken(tokenId: UUID): PersistedToken? = exposedTransaction {
        TokenDao.findById(tokenId)?.toEntity()
    }

    override fun deleteToken(tokenId: UUID) = exposedTransaction {
        TokenDao.findById(tokenId)?.delete() ?: throw NoSuchElementException("Token with id $tokenId not found")
    }

    override fun deleteToken(tokenHash: String) = exposedTransaction {
        TokenDao.find { Tokens.tokenHash eq tokenHash }
            .forEach { it.delete() }
    }

    override fun revokeTokens(userId: UUID) = exposedTransaction {
        TokenDao.find { Tokens.userId eq userId }.forEach { it.revoked = true }
    }

    override fun markTokenAsUsed(tokenId: UUID, usedAt: kotlinx.datetime.LocalDateTime) = exposedTransaction {
        TokenDao.findById(tokenId)?.apply {
            this.usedAt = usedAt
        }
    }

    override fun findTokenByHash(tokenHash: String): PersistedToken? = exposedTransaction {
        TokenDao.find { Tokens.tokenHash eq tokenHash }
            .singleOrNull()
            ?.toEntity()
    }

    override fun revokeTokenFamily(tokenFamily: UUID) = exposedTransaction {
        TokenDao.find { Tokens.tokenFamily eq tokenFamily }
            .forEach { it.revoked = true }
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
        usedAt = usedAt,
    )
}
