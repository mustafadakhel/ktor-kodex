package com.mustafadakhel.kodex.util

import io.ktor.utils.io.*

/**
 * Scope used to define JWT claims for a realm.
 */
public interface ClaimsConfigScope {
    public fun audience(audience: String)

    public fun issuer(issuer: String)

    public fun claim(key: String, value: Any)
}

@KtorDsl
internal class ClaimsConfig : ClaimsConfigScope {

    internal val additionalClaims: HashMap<String, Any> = hashMapOf()
    internal var issuer: String? = null
        private set
    internal var audience: String? = null
        private set

    override fun audience(audience: String) {
        this.audience = audience
    }

    override fun issuer(issuer: String) {
        this.issuer = issuer
    }

    override fun claim(key: String, value: Any) {
        additionalClaims[key] = value
    }
}