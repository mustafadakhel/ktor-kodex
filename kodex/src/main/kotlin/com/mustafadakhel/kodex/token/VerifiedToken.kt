package com.mustafadakhel.kodex.token

import com.mustafadakhel.kodex.model.Claim
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.TokenType
import java.util.*

internal data class VerifiedToken(
    val userId: UUID,
    val tokenId: UUID,
    val type: TokenType,
    val roles: List<Role>,
    val claims: List<Claim<*>>,
)