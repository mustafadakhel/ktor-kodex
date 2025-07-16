package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SecretsConfigScopeTest : StringSpec({
    "randomWithKid returns consistent secret for kid" {
        val secretsConfig = SecretsConfig()
        val realmScope = RealmConfigScope(Realm("test"))
        secretsConfig.run { realmScope.raw("first", "second", "third") }

        val secrets = secretsConfig.secrets()
        val (secret, kid) = secretsConfig.randomWithKid()

        secrets shouldContain secret
        val index = kid.toIntOrNull()
        index shouldNotBe null
        secrets[index!!] shouldBe secret
        secretsConfig.secretForKid(kid) shouldBe secret
    }

    "secretForKid handles invalid kid values" {
        val secretsConfig = SecretsConfig()
        val realmScope = RealmConfigScope(Realm("test"))
        secretsConfig.run { realmScope.raw("only") }

        secretsConfig.secretForKid("abc") shouldBe null
        secretsConfig.secretForKid("1") shouldBe null
        secretsConfig.secretForKid("-1") shouldBe null
    }
})
