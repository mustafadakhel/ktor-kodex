package com.mustafadakhel.kodex.model.database

import com.mustafadakhel.kodex.util.hikariDataSource
import com.mustafadakhel.kodex.util.setupExposedEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.contain
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class UserCustomAttributesValidationTest : DescribeSpec({

    val dataSource = hikariDataSource()
    val engine = setupExposedEngine(dataSource)

    beforeEach {
        transaction {
            // Create test user
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            UserDao.new {
                passwordHash = "hash"
                createdAt = now
                updatedAt = now
            }
        }
    }

    afterEach {
        transaction {
            UserCustomAttributesDao.all().forEach { it.delete() }
            UserDao.all().forEach { it.delete() }
        }
    }

    afterSpec {
        engine.clear()
    }

    describe("Custom Attribute Key Validation") {

        describe("Valid keys") {

            it("should accept alphanumeric keys") {
                val userId = transaction { UserDao.all().first().id.value }

                transaction {
                    UserCustomAttributesDao.createForUser(
                        userId,
                        mapOf("myKey123" to "value")
                    )
                }

                transaction {
                    val attrs = UserCustomAttributesDao.findByUserId(userId)
                    attrs.size shouldBe 1
                    attrs.first().key shouldBe "myKey123"
                }
            }

            it("should accept keys with underscores") {
                val userId = transaction { UserDao.all().first().id.value }

                transaction {
                    UserCustomAttributesDao.createForUser(
                        userId,
                        mapOf("my_key_name" to "value")
                    )
                }

                transaction {
                    val attrs = UserCustomAttributesDao.findByUserId(userId)
                    attrs.first().key shouldBe "my_key_name"
                }
            }

            it("should accept keys with hyphens") {
                val userId = transaction { UserDao.all().first().id.value }

                transaction {
                    UserCustomAttributesDao.createForUser(
                        userId,
                        mapOf("my-key-name" to "value")
                    )
                }

                transaction {
                    val attrs = UserCustomAttributesDao.findByUserId(userId)
                    attrs.first().key shouldBe "my-key-name"
                }
            }
        }

        describe("Invalid characters") {

            it("should reject keys with SQL injection characters") {
                val userId = transaction { UserDao.all().first().id.value }

                val exception = shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.createForUser(
                            userId,
                            mapOf("key'; DROP TABLE users--" to "value")
                        )
                    }
                }

                exception.message should contain("invalid characters")
            }

            it("should reject keys with quotes") {
                val userId = transaction { UserDao.all().first().id.value }

                shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.createForUser(
                            userId,
                            mapOf("key\"value" to "value")
                        )
                    }
                }

                shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.createForUser(
                            userId,
                            mapOf("key'value" to "value")
                        )
                    }
                }
            }

            it("should reject keys with special characters") {
                val userId = transaction { UserDao.all().first().id.value }

                val invalidKeys = listOf(
                    "key" + "@" + "value",
                    "key#value",
                    "key\$value",
                    "key%value",
                    "key&value",
                    "key*value",
                    "key(value",
                    "key)value",
                    "key=value",
                    "key+value",
                    "key[value",
                    "key]value",
                    "key{value",
                    "key}value",
                    "key\\value",
                    "key|value",
                    "key;value",
                    "key:value",
                    "key<value",
                    "key>value",
                    "key/value",
                    "key?value",
                    "key value" // space
                )

                invalidKeys.forEach { invalidKey ->
                    shouldThrow<IllegalArgumentException> {
                        transaction {
                            UserCustomAttributesDao.createForUser(
                                userId,
                                mapOf(invalidKey to "value")
                            )
                        }
                    }
                }
            }

            it("should reject blank keys") {
                val userId = transaction { UserDao.all().first().id.value }

                shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.createForUser(
                            userId,
                            mapOf("" to "value")
                        )
                    }
                }

                shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.createForUser(
                            userId,
                            mapOf("   " to "value")
                        )
                    }
                }
            }
        }

        describe("Blocked keys") {

            it("should reject __proto__") {
                val userId = transaction { UserDao.all().first().id.value }

                val exception = shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.createForUser(
                            userId,
                            mapOf("__proto__" to "malicious")
                        )
                    }
                }

                exception.message should contain("blocked for security reasons")
            }

            it("should reject constructor") {
                val userId = transaction { UserDao.all().first().id.value }

                shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.createForUser(
                            userId,
                            mapOf("constructor" to "malicious")
                        )
                    }
                }
            }

            it("should reject prototype") {
                val userId = transaction { UserDao.all().first().id.value }

                shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.createForUser(
                            userId,
                            mapOf("prototype" to "malicious")
                        )
                    }
                }
            }

            it("should reject blocked keys case-insensitively") {
                val userId = transaction { UserDao.all().first().id.value }

                val blockedVariants = listOf(
                    "__PROTO__",
                    "__Proto__",
                    "CONSTRUCTOR",
                    "Constructor",
                    "PROTOTYPE",
                    "Prototype"
                )

                blockedVariants.forEach { blockedKey ->
                    shouldThrow<IllegalArgumentException> {
                        transaction {
                            UserCustomAttributesDao.createForUser(
                                userId,
                                mapOf(blockedKey to "value")
                            )
                        }
                    }
                }
            }
        }

        describe("Length limits") {

            it("should enforce key length limit") {
                val userId = transaction { UserDao.all().first().id.value }

                // 100 characters is the max
                val validKey = "a".repeat(100)
                transaction {
                    UserCustomAttributesDao.createForUser(
                        userId,
                        mapOf(validKey to "value")
                    )
                }

                // 101 characters should fail
                val tooLongKey = "a".repeat(101)
                val exception = shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.createForUser(
                            userId,
                            mapOf(tooLongKey to "value")
                        )
                    }
                }

                exception.message should contain("too long")
            }

            it("should enforce value length limit") {
                val userId = transaction { UserDao.all().first().id.value }

                // 4096 characters is the max
                val validValue = "a".repeat(4096)
                transaction {
                    UserCustomAttributesDao.createForUser(
                        userId,
                        mapOf("key" to validValue)
                    )
                }

                // 4097 characters should fail
                val tooLongValue = "a".repeat(4097)
                val exception = shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.createForUser(
                            userId,
                            mapOf("key2" to tooLongValue)
                        )
                    }
                }

                exception.message should contain("too long")
            }
        }

        describe("replaceAllForUser validation") {

            it("should validate keys in replaceAllForUser") {
                val userId = transaction { UserDao.all().first().id.value }

                shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.replaceAllForUser(
                            userId,
                            mapOf("invalid@key" to "value")
                        )
                    }
                }
            }
        }

        describe("updateForUser validation") {

            it("should validate keys in updateForUser") {
                val userId = transaction { UserDao.all().first().id.value }

                shouldThrow<IllegalArgumentException> {
                    transaction {
                        UserCustomAttributesDao.updateForUser(
                            userId,
                            mapOf("invalid@key" to "value")
                        )
                    }
                }
            }
        }
    }
})
