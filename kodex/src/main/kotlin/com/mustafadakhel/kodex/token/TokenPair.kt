package com.mustafadakhel.kodex.token

import java.util.UUID

public data class TokenPair(
    val access: String,
    val refresh: String,
)

public data class TokenPairWithFamily(
    val tokenPair: TokenPair,
    val tokenFamily: UUID
)
