package com.mustafadakhel.kodex.model

import com.mustafadakhel.kodex.util.ClaimsConfig
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.toUuidOrNull
import kotlinx.datetime.toJavaInstant

internal class JwtClaimsValidator(
    private val claimProvider: ClaimsConfig,
    private val realm: Realm,
) : ClaimsValidator {
    override fun validate(
        claims: List<Claim<*>>,
        expectedType: TokenType,
    ): Boolean {
        val now = CurrentKotlinInstant.toJavaInstant()
        if (claims.none { it is Claim.Roles }) return false
        return claims.all { claim ->
            when (claim) {
                is Claim.ExpiresAt -> claim.toDateOrNull()?.let {
                    it.toInstant() > now
                } ?: false

                is Claim.Issuer -> claimProvider.issuer == claim.value
                is Claim.Audience -> claimProvider.audience == claim.value
                is Claim.Roles -> claim.value.isNotEmpty()
                is Claim.JwtId -> claim.value?.toUuidOrNull() != null
                is Claim.TokenType -> claim == expectedType.claim
                is Claim.Custom -> claimProvider.additionalClaims.all {
                    claim.value[it.key] == it.value
                }

                is Claim.NotBefore -> claim.value?.let {
                    java.time.Instant.ofEpochSecond(it) <= now
                } ?: true

                is Claim.IssuedAt -> true
                is Claim.AuthenticationMethods -> true

                is Claim.Subject -> claim.value?.toUuidOrNull() != null
                is Claim.Realm -> claim.value == realm.name
                is Claim.Unknown -> true
            }
        }
    }
}