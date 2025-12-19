package com.mustafadakhel.kodex.mfa.totp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlin.time.Duration.Companion.seconds

class TotpTest : FunSpec({

    test("should generate valid TOTP secret") {
        val generator = TotpGenerator()

        val secret = generator.generateSecret()

        secret shouldNotBe null
        secret.length shouldBe 32
        secret shouldMatch Regex("[A-Z2-7=]+")
    }

    test("should generate 6-digit TOTP code") {
        val generator = TotpGenerator(digits = 6)
        val secret = generator.generateSecret()

        val code = generator.generateCode(secret)

        code shouldHaveLength 6
        code shouldMatch Regex("\\d{6}")
    }

    test("should generate 8-digit TOTP code") {
        val generator = TotpGenerator(digits = 8)
        val secret = generator.generateSecret()

        val code = generator.generateCode(secret)

        code shouldHaveLength 8
        code shouldMatch Regex("\\d{8}")
    }

    test("should generate same code for same timestamp") {
        val generator = TotpGenerator()
        val secret = generator.generateSecret()
        val timestamp = CurrentKotlinInstant

        val code1 = generator.generateCode(secret, timestamp)
        val code2 = generator.generateCode(secret, timestamp)

        code1 shouldBe code2
    }

    test("should validate correct TOTP code") {
        val generator = TotpGenerator()
        val validator = TotpValidator(generator)
        val secret = generator.generateSecret()
        val timestamp = CurrentKotlinInstant

        val code = generator.generateCode(secret, timestamp)
        val isValid = validator.validate(secret, code, timestamp)

        isValid shouldBe true
    }

    test("should reject invalid TOTP code") {
        val generator = TotpGenerator()
        val validator = TotpValidator(generator)
        val secret = generator.generateSecret()

        val isValid = validator.validate(secret, "000000")

        isValid shouldBe false
    }

    test("should validate code within time window") {
        val generator = TotpGenerator(period = 30.seconds)
        val validator = TotpValidator(generator, timeStepWindow = 1)
        val secret = generator.generateSecret()
        val timestamp = CurrentKotlinInstant

        val code = generator.generateCode(secret, timestamp)
        val offsetTimestamp = timestamp + 29.seconds

        val isValid = validator.validate(secret, code, offsetTimestamp)
        isValid shouldBe true
    }

    test("should reject code outside time window") {
        val generator = TotpGenerator(period = 30.seconds)
        val validator = TotpValidator(generator, timeStepWindow = 1)
        val secret = generator.generateSecret()
        val timestamp = CurrentKotlinInstant

        val code = generator.generateCode(secret, timestamp)
        val farFutureTimestamp = timestamp + 90.seconds

        val isValid = validator.validate(secret, code, farFutureTimestamp)
        isValid shouldBe false
    }

    test("should generate valid QR code URI") {
        val generator = TotpGenerator()
        val secret = generator.generateSecret()

        val uri = generator.generateQrCodeUri(secret, "TestApp", "user@example.com")

        uri shouldMatch Regex("otpauth://totp/.*")
        uri shouldMatch Regex(".*secret=$secret.*")
        uri shouldMatch Regex(".*issuer=TestApp.*")
        uri shouldMatch Regex(".*digits=6.*")
        uri shouldMatch Regex(".*period=30.*")
    }

    test("should support SHA256 algorithm") {
        val generator = TotpGenerator(algorithm = TotpAlgorithm.SHA256)
        val secret = generator.generateSecret()

        val code = generator.generateCode(secret)

        code shouldHaveLength 6
        code shouldMatch Regex("\\d{6}")
    }

    test("should support SHA512 algorithm") {
        val generator = TotpGenerator(algorithm = TotpAlgorithm.SHA512)
        val secret = generator.generateSecret()

        val code = generator.generateCode(secret)

        code shouldHaveLength 6
        code shouldMatch Regex("\\d{6}")
    }

    test("should pad codes with leading zeros") {
        val generator = TotpGenerator()
        val validator = TotpValidator(generator)
        val secret = generator.generateSecret()

        val code = generator.generateCode(secret)

        code shouldHaveLength 6

        val isValid = validator.validate(secret, code.trimStart('0'))
        isValid shouldBe true
    }
})
