package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mustafadakhel.kodex.model.Claim
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.database.RoleEntity
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import com.mustafadakhel.kodex.util.ClaimsConfig
import com.mustafadakhel.kodex.util.SecretsConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.*

class JwtTokenIssuerTest : FunSpec({
    test("JwtTokenIssuer adds expected claims") {
        val realm = Realm("test-realm")
        val userId = UUID.randomUUID()
        val roles = listOf(
            RoleEntity("admin", null),
            RoleEntity("user", null)
        )

        val userRepository = mockk<UserRepository>()
        every { userRepository.findRoles(userId) } returns roles

        val secretsConfig = SecretsConfig()
        with(RealmConfigScope(realm)) {
            secretsConfig.apply {
                this@with.raw("secret")
            }
        }

        val claimsConfig = ClaimsConfig().apply {
            issuer("issuer")
            audience("audience")
        }

        val issuer = JwtTokenIssuer(
            secretsConfig = secretsConfig,
            claimsConfig = claimsConfig,
            userRepository = userRepository,
            realm = realm
        )

        val generated = issuer.issue(userId, 10000L, Claim.TokenType.AccessToken)
        val decoded = JWT.require(Algorithm.HMAC512("secret")).build().verify(generated.token)

        decoded.issuer shouldBe "issuer"
        decoded.audience shouldContainExactly listOf("audience")
        decoded.getClaim("token_type").asString() shouldBe "access"
        decoded.getClaim("roles").asList(String::class.java) shouldContainExactly roles.map { it.name }
        decoded.getClaim("realm").asString() shouldBe realm.owner
    }
})
