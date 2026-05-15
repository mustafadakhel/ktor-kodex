package com.mustafadakhel.kodex.repository

import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.contain
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Database
import java.util.*

class UserCustomAttributesValidationTest : DescribeSpec({

    lateinit var db: KodexDatabase
    lateinit var userRepository: UserRepository
    val testRealm = "test-realm"
    val now = LocalDateTime(2024, 1, 15, 10, 30)

    beforeEach {
        val database = Database.connect(
            "jdbc:h2:mem:attr_validation_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        val core = CoreSchema("test_")
        db = KodexDatabase(database, core)
        db.createSchema()
        userRepository = databaseUserRepository(db, testRealm)
        userRepository.seedRoles(listOf(Role("U", "")))
    }

    fun createUserWithAttrs(attrs: Map<String, String>): UUID {
        val result = userRepository.create(
            email = "test-${UUID.randomUUID()}@example.com",
            phone = null,
            hashedPassword = "hash",
            roleNames = listOf("U"),
            customAttributes = attrs,
            profile = null,
            currentTime = now,
        )
        return (result as UserRepository.CreateUserResult.Success).user.id
    }

    describe("Custom Attribute Key Validation") {

        describe("Valid keys") {

            it("should accept alphanumeric keys") {
                val userId = createUserWithAttrs(mapOf("myKey123" to "value"))
                val attrs = userRepository.findCustomAttributesByUserId(userId)
                attrs.size shouldBe 1
                attrs["myKey123"] shouldBe "value"
            }

            it("should accept keys with underscores") {
                val userId = createUserWithAttrs(mapOf("my_key_name" to "value"))
                val attrs = userRepository.findCustomAttributesByUserId(userId)
                attrs["my_key_name"] shouldBe "value"
            }

            it("should accept keys with hyphens") {
                val userId = createUserWithAttrs(mapOf("my-key-name" to "value"))
                val attrs = userRepository.findCustomAttributesByUserId(userId)
                attrs["my-key-name"] shouldBe "value"
            }
        }

        describe("Invalid characters") {

            it("should reject keys with SQL injection characters") {
                val exception = shouldThrow<IllegalArgumentException> {
                    createUserWithAttrs(mapOf("key'; DROP TABLE users--" to "value"))
                }
                exception.message should contain("invalid characters")
            }

            it("should reject keys with quotes") {
                shouldThrow<IllegalArgumentException> {
                    createUserWithAttrs(mapOf("key\"value" to "value"))
                }

                shouldThrow<IllegalArgumentException> {
                    createUserWithAttrs(mapOf("key'value" to "value"))
                }
            }

            it("should reject keys with special characters") {
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
                        createUserWithAttrs(mapOf(invalidKey to "value"))
                    }
                }
            }

            it("should reject blank keys") {
                shouldThrow<IllegalArgumentException> {
                    createUserWithAttrs(mapOf("" to "value"))
                }

                shouldThrow<IllegalArgumentException> {
                    createUserWithAttrs(mapOf("   " to "value"))
                }
            }
        }

        describe("Blocked keys") {

            it("should reject __proto__") {
                val exception = shouldThrow<IllegalArgumentException> {
                    createUserWithAttrs(mapOf("__proto__" to "malicious"))
                }
                exception.message should contain("blocked for security reasons")
            }

            it("should reject constructor") {
                shouldThrow<IllegalArgumentException> {
                    createUserWithAttrs(mapOf("constructor" to "malicious"))
                }
            }

            it("should reject prototype") {
                shouldThrow<IllegalArgumentException> {
                    createUserWithAttrs(mapOf("prototype" to "malicious"))
                }
            }

            it("should reject blocked keys case-insensitively") {
                val blockedVariants = listOf(
                    "__PROTO__", "__Proto__", "CONSTRUCTOR", "Constructor", "PROTOTYPE", "Prototype"
                )

                blockedVariants.forEach { blockedKey ->
                    shouldThrow<IllegalArgumentException> {
                        createUserWithAttrs(mapOf(blockedKey to "value"))
                    }
                }
            }
        }

        describe("Length limits") {

            it("should enforce key length limit") {
                // 100 characters is the max
                val validKey = "a".repeat(100)
                createUserWithAttrs(mapOf(validKey to "value"))

                // 101 characters should fail
                val tooLongKey = "a".repeat(101)
                val exception = shouldThrow<IllegalArgumentException> {
                    createUserWithAttrs(mapOf(tooLongKey to "value"))
                }
                exception.message should contain("too long")
            }

            it("should enforce value length limit") {
                // 4096 characters is the max
                val validValue = "a".repeat(4096)
                createUserWithAttrs(mapOf("key" to validValue))

                // 4097 characters should fail
                val tooLongValue = "a".repeat(4097)
                val exception = shouldThrow<IllegalArgumentException> {
                    createUserWithAttrs(mapOf("key2" to tooLongValue))
                }
                exception.message should contain("too long")
            }
        }

        describe("replaceAllCustomAttributesByUserId validation") {

            it("should validate keys in replaceAll") {
                val userId = createUserWithAttrs(mapOf("valid" to "value"))

                shouldThrow<IllegalArgumentException> {
                    userRepository.replaceAllCustomAttributesByUserId(userId, mapOf("invalid@key" to "value"))
                }
            }
        }

        describe("updateCustomAttributesByUserId validation") {

            it("should validate keys in update") {
                val userId = createUserWithAttrs(mapOf("valid" to "value"))

                shouldThrow<IllegalArgumentException> {
                    userRepository.updateCustomAttributesByUserId(userId, mapOf("invalid@key" to "value"))
                }
            }
        }
    }
})
