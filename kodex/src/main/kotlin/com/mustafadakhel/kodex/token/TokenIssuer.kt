package com.mustafadakhel.kodex.token

import com.mustafadakhel.kodex.model.Claim
import java.util.*

internal interface TokenIssuer {
    suspend fun issue(
        userId: UUID,
        validityMs: Long,
        tokenType: Claim.TokenType,
        roles: List<String>? = null
    ): GeneratedToken
}

