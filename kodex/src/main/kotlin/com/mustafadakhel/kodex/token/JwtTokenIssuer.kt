package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.mustafadakhel.kodex.model.Claim
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.token.formats.JwtTokenFormat
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import com.mustafadakhel.kodex.util.ClaimsConfig
import com.mustafadakhel.kodex.util.SecretsConfig
import org.slf4j.LoggerFactory
import java.util.UUID

internal class JwtTokenIssuer internal constructor(
    private val secretsConfig: SecretsConfig,
    private val claimsConfig: ClaimsConfig,
    private val userRepository: UserRepository,
    private val realm: Realm
) : TokenIssuer {

    private val logger = LoggerFactory.getLogger(JwtTokenIssuer::class.java)

    private val reservedClaimKeys = setOf(
        Claim.TokenType.Key, Claim.Roles.Key, Claim.Realm.Key,
        "tokenFamily", "sub", "iss", "aud", "exp", "iat", "jti"
    )

    private fun filterReserved(claims: Map<String, Any>): Map<String, Any> {
        val blocked = claims.keys.filter { it in reservedClaimKeys }
        if (blocked.isNotEmpty()) {
            logger.warn("Filtered reserved claim keys from additionalClaims: {}", blocked)
        }
        return claims.filterKeys { it !in reservedClaimKeys }
    }
    override suspend fun issue(
        userId: UUID,
        validityMs: Long,
        tokenType: Claim.TokenType,
        roles: List<String>?,
        tokenFamily: UUID?,
        additionalClaims: Map<String, Any>
    ) = generateToken(userId, validityMs, tokenType, roles, tokenFamily, additionalClaims)

    private fun generateToken(
        userId: UUID,
        validityMs: Long,
        tokenType: Claim.TokenType,
        rolesParam: List<String>?,
        tokenFamily: UUID?,
        additionalClaims: Map<String, Any> = emptyMap()
    ): GeneratedToken {
        val roles = rolesParam ?: userRepository.findRoles(userId).map { it.name }
        val (secret, kid) = secretsConfig.randomWithKid()

        val token = TokenGenerator.generate(
            JwtTokenFormat(
                issuer = claimsConfig.issuer!!,
                audience = claimsConfig.audience!!,
                subject = userId.toString(),
                secret = secret,
                validitySeconds = validityMs / 1000,
                keyId = kid,
                claims = buildMap {
                    putAll(filterReserved(claimsConfig.additionalClaims))
                    putAll(filterReserved(additionalClaims))
                    tokenType.value?.let { put(Claim.TokenType.Key, it) }
                    put(Claim.Roles.Key, roles)
                    put(Claim.Realm.Key, realm.name)
                    tokenFamily?.let { put("tokenFamily", it.toString()) }
                }
            )
        )

        return GeneratedToken(
            tokenId = UUID.fromString(JWT.decode(token).id),
            token = token
        )
    }
}
