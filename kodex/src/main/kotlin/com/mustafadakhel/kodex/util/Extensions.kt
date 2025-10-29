package com.mustafadakhel.kodex.util

import io.ktor.server.auth.jwt.*
import java.util.*

internal val JWTCredential.userId: UUID?
    get() = payload.subject.toUuidOrNull()

internal val JWTCredential.tokenId: UUID?
    get() = jwtId?.toUuidOrNull()

internal fun String.toUuidOrNull() = runCatching {
    UUID.fromString(this)
}.getOrDefault(null)


