package com.mustafadakhel.kodex.token

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
public data class TokenPair(
    val access: String,
    val refresh: String,
)

public data class TokenPairWithFamily(
    val tokenPair: TokenPair,
    val tokenFamily: UUID
)
