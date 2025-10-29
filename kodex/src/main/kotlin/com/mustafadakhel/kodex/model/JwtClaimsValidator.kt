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
        expectedRoles: List<String>,
    ): Boolean {
        return claims.all { claim ->
            when (claim) {
                is Claim.ExpiresAt -> claim.toDateOrNull()?.let {
                    it.toInstant() > CurrentKotlinInstant.toJavaInstant()
                } ?: false

                is Claim.Issuer -> claimProvider.issuer == claim.value
                is Claim.Audience -> claimProvider.audience == claim.value
                is Claim.Roles -> claim.value.containsAll(expectedRoles)
                is Claim.JwtId -> claim.value?.toUuidOrNull() != null
                is Claim.TokenType -> claim == expectedType.claim
                is Claim.Custom -> claimProvider.additionalClaims.all {
                    claim.value[it.key] == it.value
                }

                is Claim.Subject -> claim.value?.toUuidOrNull() != null
                is Claim.Realm -> claim.value == realm.owner
                is Claim.Unknown -> {
                    true
                }
            }
        }
    }
}