package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.mfa.database.MfaMethodType
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.service.passwordHashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordGenerator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.server.application.*
import io.ktor.server.testing.*
import org.apache.commons.codec.binary.Base32
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * MFA Admin Operations tests.
 * Tests admin force-remove, disable MFA, list user methods, and MFA statistics.
 */
class MfaAdminOperationsTest : FunSpec({

    test("admin can force-remove a specific MFA method from user") {
        testApplication {
            val realm = Realm("test-realm")
            val emailSender = MockEmailSender()
            val adminUserIds = mutableSetOf<UUID>()

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-admin-force-remove;DB_CLOSE_DELAY=-1;"
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
                        mfa {
                            hashingService = passwordHashingService()
                            emailMfa {
                                sender = emailSender
                            }
                            totpMfa {
                                issuer = "TestApp"
                            }
                            encryption {
                                aesGcm("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                            }
                            // Configure admin role check
                            userHasRole = { userId, role ->
                                role == "admin" && adminUserIds.contains(userId)
                            }
                        }
                    }
                }

                val services = kodex.servicesOf(realm)
                val mfaService = services.getExtensionService<MfaService>()!!

                // Create admin user
                val admin = services.users.createUser(
                    email = "admin@example.com",
                    phone = null,
                    password = "AdminPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!
                adminUserIds.add(admin.id)

                // Create target user
                val targetUser = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "UserPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Enroll email MFA for target user
                val emailEnrollResult = mfaService.enrollEmail(targetUser.id, "user@example.com", "192.168.1.1")
                val emailChallengeId = (emailEnrollResult as EnrollmentResult.CodeSent).challengeId
                val emailCode = emailSender.getLastCode("user@example.com")!!
                mfaService.verifyEmailEnrollment(targetUser.id, emailChallengeId, emailCode)

                // Enroll TOTP MFA for target user
                val totpEnrollResult = mfaService.enrollTotp(targetUser.id, "user@example.com")
                val totpCode = generateTotpCode(totpEnrollResult.secret)
                mfaService.verifyTotpEnrollment(targetUser.id, totpEnrollResult.methodId, totpCode)

                // Get methods before removal
                val methodsBefore = mfaService.getMethods(targetUser.id)
                methodsBefore shouldHaveSize 2

                val emailMethodId = methodsBefore.find { it.type.name == "EMAIL" }!!.id

                // Admin force-removes the email method
                mfaService.forceRemoveMfaMethod(admin.id, targetUser.id, emailMethodId)

                // Verify only TOTP remains
                val methodsAfter = mfaService.getMethods(targetUser.id)
                methodsAfter shouldHaveSize 1
                methodsAfter[0].type.name shouldBe "TOTP"
            }
        }
    }

    test("non-admin cannot force-remove MFA method") {
        testApplication {
            val realm = Realm("test-realm")
            val emailSender = MockEmailSender()

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-non-admin-force-remove;DB_CLOSE_DELAY=-1;"
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
                        mfa {
                            hashingService = passwordHashingService()
                            emailMfa {
                                sender = emailSender
                            }
                            encryption {
                                aesGcm("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                            }
                            // No one is admin
                            userHasRole = { _, _ -> false }
                        }
                    }
                }

                val services = kodex.servicesOf(realm)
                val mfaService = services.getExtensionService<MfaService>()!!

                // Create regular user (not admin)
                val regularUser = services.users.createUser(
                    email = "regular@example.com",
                    phone = null,
                    password = "RegularPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Create target user with MFA
                val targetUser = services.users.createUser(
                    email = "target@example.com",
                    phone = null,
                    password = "TargetPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Enroll email MFA for target user
                val emailEnrollResult = mfaService.enrollEmail(targetUser.id, "target@example.com", "192.168.1.1")
                val emailChallengeId = (emailEnrollResult as EnrollmentResult.CodeSent).challengeId
                val emailCode = emailSender.getLastCode("target@example.com")!!
                mfaService.verifyEmailEnrollment(targetUser.id, emailChallengeId, emailCode)

                val methods = mfaService.getMethods(targetUser.id)
                val methodId = methods[0].id

                // Non-admin tries to force-remove - should fail
                shouldThrow<KodexThrowable.Authorization.InsufficientPermissions> {
                    mfaService.forceRemoveMfaMethod(regularUser.id, targetUser.id, methodId)
                }

                // Method should still exist
                mfaService.getMethods(targetUser.id) shouldHaveSize 1
            }
        }
    }

    test("admin can disable all MFA for a user") {
        testApplication {
            val realm = Realm("test-realm")
            val emailSender = MockEmailSender()
            val adminUserIds = mutableSetOf<UUID>()

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-admin-disable-all;DB_CLOSE_DELAY=-1;"
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
                        mfa {
                            hashingService = passwordHashingService()
                            emailMfa {
                                sender = emailSender
                            }
                            totpMfa {
                                issuer = "TestApp"
                            }
                            encryption {
                                aesGcm("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                            }
                            userHasRole = { userId, role ->
                                role == "admin" && adminUserIds.contains(userId)
                            }
                        }
                    }
                }

                val services = kodex.servicesOf(realm)
                val mfaService = services.getExtensionService<MfaService>()!!

                // Create admin user
                val admin = services.users.createUser(
                    email = "admin@example.com",
                    phone = null,
                    password = "AdminPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!
                adminUserIds.add(admin.id)

                // Create target user
                val targetUser = services.users.createUser(
                    email = "lockedout@example.com",
                    phone = null,
                    password = "LockedOutPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Enroll email MFA
                val emailEnrollResult = mfaService.enrollEmail(targetUser.id, "lockedout@example.com", "192.168.1.1")
                val emailChallengeId = (emailEnrollResult as EnrollmentResult.CodeSent).challengeId
                val emailCode = emailSender.getLastCode("lockedout@example.com")!!
                mfaService.verifyEmailEnrollment(targetUser.id, emailChallengeId, emailCode)

                // Enroll TOTP MFA
                val totpEnrollResult = mfaService.enrollTotp(targetUser.id, "lockedout@example.com")
                val totpCode = generateTotpCode(totpEnrollResult.secret)
                mfaService.verifyTotpEnrollment(targetUser.id, totpEnrollResult.methodId, totpCode)

                // Trust a device
                mfaService.trustDevice(targetUser.id, "192.168.1.1", "Chrome/120", "Test Device")

                // Verify MFA is active
                mfaService.getMethods(targetUser.id) shouldHaveSize 2
                mfaService.hasAnyMethod(targetUser.id) shouldBe true
                mfaService.getTrustedDevices(targetUser.id) shouldHaveSize 1

                // Admin disables all MFA for the user
                mfaService.disableMfaForUser(admin.id, targetUser.id)

                // All MFA should be removed
                mfaService.getMethods(targetUser.id) shouldHaveSize 0
                mfaService.hasAnyMethod(targetUser.id) shouldBe false
                mfaService.getTrustedDevices(targetUser.id) shouldHaveSize 0
            }
        }
    }

    test("non-admin cannot disable MFA for another user") {
        testApplication {
            val realm = Realm("test-realm")
            val emailSender = MockEmailSender()

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-non-admin-disable;DB_CLOSE_DELAY=-1;"
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
                        mfa {
                            hashingService = passwordHashingService()
                            emailMfa {
                                sender = emailSender
                            }
                            encryption {
                                aesGcm("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                            }
                            userHasRole = { _, _ -> false }
                        }
                    }
                }

                val services = kodex.servicesOf(realm)
                val mfaService = services.getExtensionService<MfaService>()!!

                // Create regular user
                val regularUser = services.users.createUser(
                    email = "regular@example.com",
                    phone = null,
                    password = "RegularPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Create target user with MFA
                val targetUser = services.users.createUser(
                    email = "target@example.com",
                    phone = null,
                    password = "TargetPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Enroll MFA
                val emailEnrollResult = mfaService.enrollEmail(targetUser.id, "target@example.com", "192.168.1.1")
                val emailChallengeId = (emailEnrollResult as EnrollmentResult.CodeSent).challengeId
                val emailCode = emailSender.getLastCode("target@example.com")!!
                mfaService.verifyEmailEnrollment(targetUser.id, emailChallengeId, emailCode)

                // Non-admin tries to disable MFA - should fail
                shouldThrow<KodexThrowable.Authorization.InsufficientPermissions> {
                    mfaService.disableMfaForUser(regularUser.id, targetUser.id)
                }

                // MFA should still be active
                mfaService.getMethods(targetUser.id) shouldHaveSize 1
            }
        }
    }

    test("admin can list user's MFA methods") {
        testApplication {
            val realm = Realm("test-realm")
            val emailSender = MockEmailSender()
            val adminUserIds = mutableSetOf<UUID>()

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-admin-list-methods;DB_CLOSE_DELAY=-1;"
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
                        mfa {
                            hashingService = passwordHashingService()
                            emailMfa {
                                sender = emailSender
                            }
                            totpMfa {
                                issuer = "TestApp"
                            }
                            encryption {
                                aesGcm("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                            }
                            userHasRole = { userId, role ->
                                role == "admin" && adminUserIds.contains(userId)
                            }
                        }
                    }
                }

                val services = kodex.servicesOf(realm)
                val mfaService = services.getExtensionService<MfaService>()!!

                // Create admin user
                val admin = services.users.createUser(
                    email = "admin@example.com",
                    phone = null,
                    password = "AdminPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!
                adminUserIds.add(admin.id)

                // Create target user with MFA
                val targetUser = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "UserPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Enroll email MFA
                val emailEnrollResult = mfaService.enrollEmail(targetUser.id, "user@example.com", "192.168.1.1")
                val emailChallengeId = (emailEnrollResult as EnrollmentResult.CodeSent).challengeId
                val emailCode = emailSender.getLastCode("user@example.com")!!
                mfaService.verifyEmailEnrollment(targetUser.id, emailChallengeId, emailCode)

                // Enroll TOTP MFA
                val totpEnrollResult = mfaService.enrollTotp(targetUser.id, "user@example.com")
                val totpCode = generateTotpCode(totpEnrollResult.secret)
                mfaService.verifyTotpEnrollment(targetUser.id, totpEnrollResult.methodId, totpCode)

                // Admin lists the user's MFA methods
                val methods = mfaService.listUserMethods(admin.id, targetUser.id)
                methods shouldHaveSize 2

                val types = methods.map { it.type.name }
                types.contains("EMAIL") shouldBe true
                types.contains("TOTP") shouldBe true
            }
        }
    }

    test("non-admin cannot list another user's MFA methods") {
        testApplication {
            val realm = Realm("test-realm")
            val emailSender = MockEmailSender()

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-non-admin-list;DB_CLOSE_DELAY=-1;"
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
                        mfa {
                            hashingService = passwordHashingService()
                            emailMfa {
                                sender = emailSender
                            }
                            encryption {
                                aesGcm("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                            }
                            userHasRole = { _, _ -> false }
                        }
                    }
                }

                val services = kodex.servicesOf(realm)
                val mfaService = services.getExtensionService<MfaService>()!!

                // Create regular user
                val regularUser = services.users.createUser(
                    email = "regular@example.com",
                    phone = null,
                    password = "RegularPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Create target user with MFA
                val targetUser = services.users.createUser(
                    email = "target@example.com",
                    phone = null,
                    password = "TargetPass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Enroll MFA for target
                val emailEnrollResult = mfaService.enrollEmail(targetUser.id, "target@example.com", "192.168.1.1")
                val emailChallengeId = (emailEnrollResult as EnrollmentResult.CodeSent).challengeId
                val emailCode = emailSender.getLastCode("target@example.com")!!
                mfaService.verifyEmailEnrollment(targetUser.id, emailChallengeId, emailCode)

                // Non-admin tries to list methods - should fail
                shouldThrow<KodexThrowable.Authorization.InsufficientPermissions> {
                    mfaService.listUserMethods(regularUser.id, targetUser.id)
                }
            }
        }
    }

    test("MFA statistics should return accurate data") {
        testApplication {
            val realm = Realm("test-realm")
            val emailSender = MockEmailSender()
            var totalUsersFunc: Long = 0

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-statistics;DB_CLOSE_DELAY=-1;"
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
                        mfa {
                            hashingService = passwordHashingService()
                            emailMfa {
                                sender = emailSender
                            }
                            totpMfa {
                                issuer = "TestApp"
                            }
                            encryption {
                                aesGcm("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                            }
                            // Configure total users function for statistics
                            getTotalUsers = { totalUsersFunc }
                        }
                    }
                }

                val services = kodex.servicesOf(realm)
                val mfaService = services.getExtensionService<MfaService>()!!

                // Create multiple users
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

                val user3 = services.users.createUser(
                    email = "user3@example.com",
                    phone = null,
                    password = "User3Pass123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                totalUsersFunc = 3

                // User1: Email MFA only
                val email1Result = mfaService.enrollEmail(user1.id, "user1@example.com", "192.168.1.1")
                val email1ChallengeId = (email1Result as EnrollmentResult.CodeSent).challengeId
                val email1Code = emailSender.getLastCode("user1@example.com")!!
                mfaService.verifyEmailEnrollment(user1.id, email1ChallengeId, email1Code)

                // User2: TOTP MFA only
                val totpResult = mfaService.enrollTotp(user2.id, "user2@example.com")
                val totpCode = generateTotpCode(totpResult.secret)
                mfaService.verifyTotpEnrollment(user2.id, totpResult.methodId, totpCode)

                // User2 also trusts a device
                mfaService.trustDevice(user2.id, "192.168.1.2", "Firefox/121", "User2 Device")

                // User3: No MFA (left as-is)

                // Get statistics
                val stats = mfaService.getMfaStatistics()

                // Total users should come from configured function
                stats.totalUsers shouldBe 3

                // 2 out of 3 users have MFA
                stats.usersWithMfa shouldBe 2

                // Adoption rate should be ~66.67% (2/3) - expressed as percentage (multiplied by 100)
                stats.adoptionRate shouldBeGreaterThanOrEqual 66.0
                stats.adoptionRate shouldBeLessThanOrEqual 67.0

                // Method distribution
                stats.methodDistribution[MfaMethodType.EMAIL] shouldBe 1
                stats.methodDistribution[MfaMethodType.TOTP] shouldBe 1

                // Trusted devices count
                stats.trustedDevices shouldBeGreaterThanOrEqual 1
            }
        }
    }
})

private fun generateTotpCode(base32Secret: String): String {
    val secretBytes = Base32().decode(base32Secret)
    val config = TimeBasedOneTimePasswordConfig(
        codeDigits = 6,
        hmacAlgorithm = HmacAlgorithm.SHA1,
        timeStep = 30,
        timeStepUnit = TimeUnit.SECONDS
    )
    val generator = TimeBasedOneTimePasswordGenerator(secretBytes, config)
    return generator.generate()
}
