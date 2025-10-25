package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.service.Argon2id
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PasswordHashingConfigScopeTest : DescribeSpec({

    describe("PasswordHashingConfigScope") {
        it("should have default balanced algorithm") {
            val scope = PasswordHashingConfigScope()

            scope.algorithm shouldNotBe null
        }

        it("should allow setting springSecurity algorithm") {
            val scope = PasswordHashingConfigScope()
            val algorithm = Argon2id.springSecurity()

            scope.algorithm = algorithm

            scope.algorithm shouldBe algorithm
        }

        it("should allow setting balanced algorithm") {
            val scope = PasswordHashingConfigScope()
            val algorithm = Argon2id.balanced()

            scope.algorithm = algorithm

            scope.algorithm shouldBe algorithm
        }

        it("should allow setting keycloak algorithm") {
            val scope = PasswordHashingConfigScope()
            val algorithm = Argon2id.keycloak()

            scope.algorithm = algorithm

            scope.algorithm shouldBe algorithm
        }

        it("should allow setting owaspMinimum algorithm") {
            val scope = PasswordHashingConfigScope()
            val algorithm = Argon2id.owaspMinimum()

            scope.algorithm = algorithm

            scope.algorithm shouldBe algorithm
        }

        it("should allow setting custom algorithm") {
            val scope = PasswordHashingConfigScope()
            val customAlgorithm = Argon2id(
                memory = 32768,
                iterations = 3,
                parallelism = 2,
                saltLength = 16,
                hashLength = 32
            )

            scope.algorithm = customAlgorithm

            scope.algorithm shouldBe customAlgorithm
        }

        it("should build config with algorithm") {
            val scope = PasswordHashingConfigScope()
            val algorithm = Argon2id.springSecurity()
            scope.algorithm = algorithm

            val config = scope.build()

            config.algorithm shouldBe algorithm
        }

        it("should build config with default algorithm") {
            val scope = PasswordHashingConfigScope()

            val config = scope.build()

            config.algorithm shouldNotBe null
        }
    }

    describe("PasswordHashingConfig data class") {
        it("should store algorithm") {
            val algorithm = Argon2id.balanced()
            val config = PasswordHashingConfig(algorithm)

            config.algorithm shouldBe algorithm
        }

        it("should support data class equality") {
            val algorithm = Argon2id.keycloak()
            val config1 = PasswordHashingConfig(algorithm)
            val config2 = PasswordHashingConfig(algorithm)

            (config1 == config2) shouldBe true
        }

        it("should support data class copy") {
            val algorithm1 = Argon2id.balanced()
            val algorithm2 = Argon2id.springSecurity()
            val original = PasswordHashingConfig(algorithm1)
            val copy = original.copy(algorithm = algorithm2)

            copy.algorithm shouldBe algorithm2
        }
    }
})
