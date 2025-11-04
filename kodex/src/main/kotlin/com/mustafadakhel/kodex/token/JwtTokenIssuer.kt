package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.mustafadakhel.kodex.model.Claim
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.token.formats.JwtTokenFormat
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import com.mustafadakhel.kodex.util.ClaimsConfig
import com.mustafadakhel.kodex.util.SecretsConfig
import java.util.UUID

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

        val token = TokenGenerator.generate(
            JwtTokenFormat(
                issuer = claimsConfig.issuer!!,
                audience = claimsConfig.audience!!,
                subject = userId.toString(),
                secret = secret,
                validitySeconds = validityMs / 1000,
                keyId = kid,
                claims = buildMap {
                    tokenType.value?.let { put(Claim.TokenType.Key, it) }
                    put(Claim.Roles.Key, roles)
                    put(Claim.Realm.Key, realm.owner)
                    putAll(claimsConfig.additionalClaims)
                }
            )
        )

        return GeneratedToken(
            tokenId = UUID.fromString(JWT.decode(token).id),
            token = token
        )
    }
}
