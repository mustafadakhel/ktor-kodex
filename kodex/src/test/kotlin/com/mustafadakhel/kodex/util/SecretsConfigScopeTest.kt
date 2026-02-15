package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class SecretsConfigScopeTest : StringSpec({
    // Secrets must be at least 32 characters
    val secret1 = "first-secret-value-that-is-at-least-32-chars-long"
    val secret2 = "second-secret-value-that-is-at-least-32-chars"
    val secret3 = "third-secret-value-that-is-at-least-32-chars-"
    val onlySecret = "only-secret-value-that-is-at-least-32-chars-long"

    "randomWithKid returns consistent secret for kid" {
        val secretsConfig = SecretsConfig()
        val realmScope = RealmConfigScope(Realm("test"))
        secretsConfig.run { realmScope.raw(secret1, secret2, secret3) }

        val secrets = secretsConfig.secrets()
        val (secret, kid) = secretsConfig.randomWithKid()

        secrets shouldContain secret
        kid shouldMatch "[0-9a-f]{16}"
        secretsConfig.secretForKid(kid) shouldBe secret
    }

    "secretForKid handles invalid kid values" {
        val secretsConfig = SecretsConfig()
        val realmScope = RealmConfigScope(Realm("test"))
        secretsConfig.run { realmScope.raw(onlySecret) }

        secretsConfig.secretForKid("abc") shouldBe null
        secretsConfig.secretForKid("1") shouldBe null
        secretsConfig.secretForKid("-1") shouldBe null
        secretsConfig.secretForKid("0000000000000000") shouldBe null
    }
})
