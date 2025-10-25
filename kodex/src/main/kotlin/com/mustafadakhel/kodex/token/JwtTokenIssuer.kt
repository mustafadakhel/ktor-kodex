package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mustafadakhel.kodex.model.Claim
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.util.ClaimsConfig
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.SecretsConfig
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaInstant
import java.util.*

internal class JwtTokenIssuer internal constructor(
    private val secretsConfig: SecretsConfig,
    private val claimsConfig: ClaimsConfig,
    private val userRepository: UserRepository,
    private val realm: Realm
) : TokenIssuer {
    override suspend fun issue(
        userId: UUID,
        validityMs: Long,
        tokenType: Claim.TokenType,
        roles: List<String>?
    ) = generateToken(userId, validityMs, tokenType, roles)

    private fun generateToken(
        userId: UUID,
        validityMs: Long,
        tokenType: Claim.TokenType,
        rolesParam: List<String>?
    ): GeneratedToken {
        val roles = rolesParam ?: userRepository.findRoles(userId).map { it.name }
        val (secret, kid) = secretsConfig.randomWithKid()
        val algorithm = Algorithm.HMAC512(secret)
        val validity = CurrentKotlinInstant.plus(validityMs, DateTimeUnit.MILLISECOND)
        val id = UUID.randomUUID()
        val token = JWT.create()
            .withIssuer(claimsConfig.issuer)
            .withAudience(claimsConfig.audience)
            .withKeyId(kid)
            .withJWTId(id.toString())
            .withSubject(userId.toString())
            .withClaim(Claim.Custom.Key, claimsConfig.additionalClaims)
            .withClaim(Claim.TokenType.Key, tokenType.value)
            .withArrayClaim(Claim.Roles.Key, roles.toTypedArray())
            .withClaim(Claim.Realm.Key, realm.owner)
            .withExpiresAt(validity.toJavaInstant())
            .sign(algorithm)
        return GeneratedToken(
            tokenId = id,
            token = token
        )
    }
}
