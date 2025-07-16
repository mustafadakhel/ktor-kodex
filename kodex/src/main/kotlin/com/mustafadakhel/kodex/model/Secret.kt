package com.mustafadakhel.kodex.model

import io.ktor.server.config.*

public sealed interface Secrets {
    public data class FromEnv(val keys: List<String>, val applicationConfig: ApplicationConfig) : Secrets
    public data class Raw(val secrets: List<String>) : Secrets
}
