package com.mustafadakhel.kodex.model.database

import com.mustafadakhel.kodex.model.TokenType
import kotlinx.datetime.LocalDateTime
import java.util.*

internal data class PersistedToken(
    val id: UUID,
    val userId: UUID,
    val tokenHash: String,
    val type: TokenType,
    val revoked: Boolean,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val tokenFamily: UUID? = null,
    val parentTokenId: UUID? = null,
    val firstUsedAt: LocalDateTime? = null,
    val lastUsedAt: LocalDateTime? = null,
)
