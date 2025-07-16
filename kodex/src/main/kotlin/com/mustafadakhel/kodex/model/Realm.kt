package com.mustafadakhel.kodex.model

public data class Realm(val owner: String) {
    internal val authProviderName: String
        get() = "auth-jwt-${owner}"
    internal val name: String
        get() = "access to $owner realm"
}
