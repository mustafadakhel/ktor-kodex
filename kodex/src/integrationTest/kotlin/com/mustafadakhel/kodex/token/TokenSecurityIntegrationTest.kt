package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.throwable.KodexThrowable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.*
import io.ktor.server.testing.*
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Token security integration tests.
 * Tests token expiration, revocation, multi-realm isolation, and persistence modes.
 */
class TokenSecurityIntegrationTest : FunSpec({

    test("token should be valid before expiration") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:token-expiry-valid;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                services.users.createUser(
                    email = "expiry@example.com",
                    phone = null,
                    password = "ExpiryTest123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                val tokens = services.auth.login(
                    "expiry@example.com",
                    "ExpiryTest123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                // Token should be valid immediately after issuance
                val principal = services.tokens.verify(tokens.access)
                principal.shouldNotBeNull()
            }
        }
    }

    test("single token revocation should invalidate only that token") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:single-revoke;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                        // Enable token persistence so revocation works
                        tokenValidity {
                            persist(TokenType.AccessToken, true)
                            persist(TokenType.RefreshToken, true)
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                services.users.createUser(
                    email = "revoke@example.com",
                    phone = null,
                    password = "RevokeTest123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                // Login twice to get two different tokens
                val tokens1 = services.auth.login(
                    "revoke@example.com",
                    "RevokeTest123!",
                    "192.168.1.1",
                    "Chrome/120"
                )

                val tokens2 = services.auth.login(
                    "revoke@example.com",
                    "RevokeTest123!",
                    "192.168.1.2",
                    "Firefox/121"
                )

                // Both should be valid initially
                services.tokens.verify(tokens1.access).shouldNotBeNull()
                services.tokens.verify(tokens2.access).shouldNotBeNull()

                // Revoke only the first token (delete=false to mark as revoked)
                services.tokens.revokeToken(tokens1.access, delete = false)

                // First token should now be invalid
                services.tokens.verify(tokens1.access).shouldBeNull()

                // Second token should still be valid
                services.tokens.verify(tokens2.access).shouldNotBeNull()
            }
        }
    }

    test("revoking all user tokens should invalidate all sessions") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:revoke-all;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                        // Enable token persistence so revocation works
                        tokenValidity {
                            persist(TokenType.AccessToken, true)
                            persist(TokenType.RefreshToken, true)
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "revokeall@example.com",
                    phone = null,
                    password = "RevokeAllTest123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Login multiple times from different devices
                val tokens1 = services.auth.login(
                    "revokeall@example.com",
                    "RevokeAllTest123!",
                    "192.168.1.1",
                    "Chrome/120"
                )

                val tokens2 = services.auth.login(
                    "revokeall@example.com",
                    "RevokeAllTest123!",
                    "192.168.1.2",
                    "Safari/17"
                )

                val tokens3 = services.auth.login(
                    "revokeall@example.com",
                    "RevokeAllTest123!",
                    "10.0.0.1",
                    "Firefox/121"
                )

                // All should be valid initially
                services.tokens.verify(tokens1.access).shouldNotBeNull()
                services.tokens.verify(tokens2.access).shouldNotBeNull()
                services.tokens.verify(tokens3.access).shouldNotBeNull()

                // Revoke all tokens for this user
                services.tokens.revoke(user.id)

                // All tokens should now be invalid
                services.tokens.verify(tokens1.access).shouldBeNull()
                services.tokens.verify(tokens2.access).shouldBeNull()
                services.tokens.verify(tokens3.access).shouldBeNull()
            }
        }
    }

    test("token from one realm should not work in another realm") {
        testApplication {
            val realmA = Realm("realm-a")
            val realmB = Realm("realm-b")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:multi-realm-test;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realmA) {
                        claims {
                            issuer("issuer-a")
                            audience("audience-a")
                        }
                        secrets {
                            raw("realm-a-secret-key-32-chars!!!!")
                        }
                    }
                    realm(realmB) {
                        claims {
                            issuer("issuer-b")
                            audience("audience-b")
                        }
                        secrets {
                            raw("realm-b-secret-key-32-chars!!!!")
                        }
                    }
                }

                val servicesA = kodex.servicesOf(realmA)
                val servicesB = kodex.servicesOf(realmB)

                // Create user in realm A
                servicesA.users.createUser(
                    email = "userA@example.com",
                    phone = null,
                    password = "TestPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                // Create different user in realm B
                servicesB.users.createUser(
                    email = "userB@example.com",
                    phone = null,
                    password = "TestPass456!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                // Get token from realm A
                val tokensA = servicesA.auth.login(
                    "userA@example.com",
                    "TestPass123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                // Token should be valid in realm A
                val principalInA = servicesA.tokens.verify(tokensA.access)
                principalInA.shouldNotBeNull()

                // Token from realm A should NOT be valid in realm B
                // (different secret, different claims validator)
                val principalInB = servicesB.tokens.verify(tokensA.access)
                principalInB.shouldBeNull()
            }
        }
    }

    test("token with wrong signature should be rejected") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:invalid-sig;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("correct-secret-key-32-chars!!!!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                services.users.createUser(
                    email = "sig@example.com",
                    phone = null,
                    password = "SigTest123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                // Get a valid token
                val validTokens = services.auth.login(
                    "sig@example.com",
                    "SigTest123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                // Valid token should work
                services.tokens.verify(validTokens.access).shouldNotBeNull()

                // Create a token with a different (wrong) secret
                val wrongSecretToken = JWT.create()
                    .withSubject(UUID.randomUUID().toString())
                    .withClaim("type", TokenType.AccessToken.name)
                    .withClaim("realm", "test-realm")
                    .withIssuer("test-issuer")
                    .withAudience("test-audience")
                    .sign(Algorithm.HMAC256("wrong-secret-key-32-chars-here!!!"))

                // Token with wrong signature should be rejected
                services.tokens.verify(wrongSecretToken).shouldBeNull()
            }
        }
    }

    test("completely malformed token should be rejected") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:malformed-token;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                // Various malformed tokens
                services.tokens.verify("").shouldBeNull()
                services.tokens.verify("not-a-jwt").shouldBeNull()
                services.tokens.verify("invalid.jwt.format").shouldBeNull()
                services.tokens.verify("eyJhbGciOiJIUzI1NiJ9.invalid").shouldBeNull()
            }
        }
    }

    test("token should contain user roles at issuance time") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:role-claims;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                // User gets auto-assigned realm owner role
                services.users.createUser(
                    email = "roles@example.com",
                    phone = null,
                    password = "RolesTest123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                val tokens = services.auth.login(
                    "roles@example.com",
                    "RolesTest123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                val principal = services.tokens.verify(tokens.access)
                principal.shouldNotBeNull()

                // User should have the realm owner role (auto-added)
                principal.roles.any { it.name == "test-realm" } shouldBe true
            }
        }
    }

    test("refresh token should work and return new tokens") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:refresh-test;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "refresh@example.com",
                    phone = null,
                    password = "RefreshTest123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                val originalTokens = services.auth.login(
                    "refresh@example.com",
                    "RefreshTest123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                // Refresh should return new tokens
                val refreshedTokens = services.tokens.refresh(user.id, originalTokens.refresh)
                refreshedTokens.shouldNotBeNull()

                // New access token should be different
                refreshedTokens.access shouldNotBe originalTokens.access

                // New refresh token should be different
                refreshedTokens.refresh shouldNotBe originalTokens.refresh

                // New access token should be valid
                services.tokens.verify(refreshedTokens.access).shouldNotBeNull()
            }
        }
    }

    test("using access token for refresh should fail") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:access-for-refresh;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "wrongtype@example.com",
                    phone = null,
                    password = "WrongTypeTest123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                val tokens = services.auth.login(
                    "wrongtype@example.com",
                    "WrongTypeTest123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                // Using access token for refresh should fail
                shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
                    services.tokens.refresh(user.id, tokens.access)
                }
            }
        }
    }

    test("refresh token from wrong user should fail") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:wrong-user-refresh;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                // Create two users
                val user1 = services.users.createUser(
                    email = "user1@example.com",
                    phone = null,
                    password = "User1Pass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                val user2 = services.users.createUser(
                    email = "user2@example.com",
                    phone = null,
                    password = "User2Pass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Get tokens for user1
                val user1Tokens = services.auth.login(
                    "user1@example.com",
                    "User1Pass123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                // Try to refresh user1's token as user2 - should fail
                shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
                    services.tokens.refresh(user2.id, user1Tokens.refresh)
                }
            }
        }
    }
})
