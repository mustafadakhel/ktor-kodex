package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.mfa.encryption.AesGcmSecretEncryption
import com.mustafadakhel.kodex.mfa.totp.TotpAlgorithm
import com.mustafadakhel.kodex.service.HashingService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MfaConfigTest : FunSpec({

    val mockHashingService = object : HashingService {
        override fun hash(value: String): String = "hashed_$value"
        override fun verify(value: String, hash: String): Boolean = hash == "hashed_$value"
    }

    test("should have sensible default values") {
        val config = MfaConfig()

        config.requireMfa shouldBe false
        config.codeLength shouldBe 6
        config.codeExpiration shouldBe 10.minutes
        config.maxEnrollAttemptsPerUser shouldBe 3
        config.maxVerifyAttempts shouldBe 5
        config.sessionExpiration shouldBe 5.minutes
        config.maxActiveSessions shouldBe 3
        config.totpEnabled shouldBe true
        config.totpIssuer shouldBe "KodexAuth"
        config.totpAlgorithm shouldBe TotpAlgorithm.SHA1
        config.totpDigits shouldBe 6
        config.totpPeriod shouldBe 30.seconds
        config.backupCodesCount shouldBe 10
        config.backupCodeLength shouldBe 8
    }

    test("should validate successfully with required configs") {
        val config = MfaConfig().apply {
            secretEncryption = AesGcmSecretEncryption(AesGcmSecretEncryption.generateKey())
            hashingService = mockHashingService
        }

        val result = config.validate()
        result.isValid() shouldBe true
    }

    test("should fail validation when secretEncryption is missing") {
        val config = MfaConfig().apply {
            hashingService = mockHashingService
        }

        val result = config.validate()
        result.isValid() shouldBe false
        result.errors().size shouldBe 1
        result.errors().first() shouldContain "secretEncryption"
    }

    test("should fail validation when hashingService is missing") {
        val config = MfaConfig().apply {
            secretEncryption = AesGcmSecretEncryption(AesGcmSecretEncryption.generateKey())
        }

        val result = config.validate()
        result.isValid() shouldBe false
        result.errors().size shouldBe 1
        result.errors().first() shouldContain "hashingService"
    }

    test("should fail validation with invalid codeLength") {
        val config = MfaConfig().apply {
            codeLength = 3
            secretEncryption = AesGcmSecretEncryption(AesGcmSecretEncryption.generateKey())
            hashingService = mockHashingService
        }

        val result = config.validate()
        result.isValid() shouldBe false
        result.errors().any { it.contains("codeLength") } shouldBe true
    }

    test("should fail validation with invalid totpDigits") {
        val config = MfaConfig().apply {
            totpDigits = 5
            secretEncryption = AesGcmSecretEncryption(AesGcmSecretEncryption.generateKey())
            hashingService = mockHashingService
        }

        val result = config.validate()
        result.isValid() shouldBe false
        result.errors().any { it.contains("totpDigits") } shouldBe true
    }

    test("should support email MFA configuration") {
        val config = MfaConfig()

        config.emailMfa {
            enabled = true
            sender = object : com.mustafadakhel.kodex.mfa.sender.MfaCodeSender {
                override suspend fun send(contactValue: String, code: String) {}
            }
        }

        config.emailSender shouldNotBe null
    }

    test("should support TOTP configuration") {
        val config = MfaConfig()

        config.totpMfa {
            enabled = true
            issuer = "TestApp"
            algorithm = TotpAlgorithm.SHA256
            digits = 8
            period = 60.seconds
            timeStepWindow = 2
        }

        config.totpEnabled shouldBe true
        config.totpIssuer shouldBe "TestApp"
        config.totpAlgorithm shouldBe TotpAlgorithm.SHA256
        config.totpDigits shouldBe 8
        config.totpPeriod shouldBe 60.seconds
        config.totpTimeStepWindow shouldBe 2
    }

    test("should support backup codes configuration") {
        val config = MfaConfig()

        config.backupCodes {
            codeCount = 15
            codeLength = 10
        }

        config.backupCodesCount shouldBe 15
        config.backupCodeLength shouldBe 10
    }
})
