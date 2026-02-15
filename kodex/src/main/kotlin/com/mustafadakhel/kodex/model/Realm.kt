package com.mustafadakhel.kodex.model

public data class Realm(val name: String) {
    init {
        require(name.isNotBlank()) { "Realm name must not be blank" }
        require(name.length <= 128) { "Realm name must be 128 characters or fewer" }
    }

    internal val authProviderName: String
        get() = "auth-jwt-${name}"
    internal val displayName: String
        get() = "access to $name realm"
}
