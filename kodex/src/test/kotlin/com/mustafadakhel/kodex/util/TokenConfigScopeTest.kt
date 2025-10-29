package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.TokenValidity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class TokenConfigScopeTest : StringSpec({
    "access duration should update" {
        val config = TokenConfig()
        val newDuration = 3.hours
        config.access(newDuration)
        config.validity().access shouldBe newDuration
    }

    "refresh duration should update" {
        val config = TokenConfig()
        val newDuration = 10.days
        config.refresh(newDuration)
        config.validity().refresh shouldBe newDuration
    }

    "persist should update flags" {
        val config = TokenConfig()
        config.persist(TokenType.AccessToken, true)
        config.persistenceFlags[TokenType.AccessToken] shouldBe true
        config.persist(TokenType.RefreshToken, false)
        config.persistenceFlags[TokenType.RefreshToken] shouldBe false
    }

    "validity should reflect configured durations" {
        val config = TokenConfig()
        val access = 5.hours
        val refresh = 20.days
        config.access(access)
        config.refresh(refresh)
        config.validity() shouldBe TokenValidity(access, refresh)
    }
})
