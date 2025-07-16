package com.mustafadakhel.kodex.token

import com.mustafadakhel.kodex.model.Claim
import java.util.*

internal data class DecodedToken(
    val userId: UUID?,
    val tokenId: UUID?,
    val token: String?,
    val claims: List<Claim<*>>,
)
