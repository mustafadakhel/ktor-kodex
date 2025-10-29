package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.service.Argon2id
import io.ktor.utils.io.*

internal data class PasswordHashingConfig(
    val algorithm: Argon2id
)

@KtorDsl
public class PasswordHashingConfigScope internal constructor() {
    public var algorithm: Argon2id = Argon2id.balanced()

    internal fun build(): PasswordHashingConfig = PasswordHashingConfig(
        algorithm = algorithm
    )
}
