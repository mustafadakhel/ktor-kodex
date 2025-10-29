package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.service.SaltedHashingService.SaltedHashSettings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.util.*

class SaltedHashingServiceTest : StringSpec({
    val settings = SaltedHashSettings(
        algorithm = "SHA-256",
        saltLength = 16
    )
    val hashingService = saltedHashingService(settings)

    "hash should generate unique salts" {
        val hash1 = hashingService.hash("password")
        val hash2 = hashingService.hash("password")
        val decoded1 = Base64.getDecoder().decode(hash1)
        val decoded2 = Base64.getDecoder().decode(hash2)
        val salt1 = decoded1.copyOfRange(0, settings.saltLength)
        val salt2 = decoded2.copyOfRange(0, settings.saltLength)
        salt1.contentEquals(salt2).shouldBeFalse()
    }

    "verify should return true for matching values" {
        val hash = hashingService.hash("secret")
        hashingService.verify("secret", hash).shouldBeTrue()
    }

    "verify should return false for non matching values" {
        val hash = hashingService.hash("secret")
        hashingService.verify("other", hash).shouldBeFalse()
    }
})
