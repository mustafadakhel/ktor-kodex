package com.mustafadakhel.kodex.util

import io.ktor.utils.io.*

/**
 * Scope used to define JWT claims for a realm.
 */
public interface ClaimsConfigScope {
    /** Sets the audience claim for generated tokens. */
    public fun audience(audience: String)

    /** Sets the issuer claim for generated tokens. */
    public fun issuer(issuer: String)

    /** Adds a static claim with the given [key] and [value]. */
    public fun claim(key: String, value: Any)
}

@KtorDsl
/** Internal builder implementing [ClaimsConfigScope]. */
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