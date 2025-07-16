package com.mustafadakhel.kodex.routes.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.mockk.every
import io.mockk.mockk
import java.util.*

class IdScopesTest : FunSpec({
    test("CallParameterId.getOrNull parses UUID parameter") {
        val id = UUID.randomUUID()
        val call = mockk<ApplicationCall>()
        every { call.parameters } returns parametersOf("id", id.toString())

        CallParameterId.getOrNull(call) shouldBe id
    }

    test("CallParameterId.getOrNull returns null for malformed UUID") {
        val call = mockk<ApplicationCall>()
        every { call.parameters } returns parametersOf("id", "bad")

        CallParameterId.getOrNull(call) shouldBe null
    }

    test("CallParameterId.getOrNull throws when parameter missing") {
        val call = mockk<ApplicationCall>()
        every { call.parameters } returns parametersOf()

        shouldThrow<MissingRequestParameterException> {
            CallParameterId.getOrNull(call)
        }
    }

    test("CallParameterId.idOrFail throws BadRequestException for invalid UUID") {
        val call = mockk<ApplicationCall>()
        every { call.parameters } returns parametersOf("id", "bad")

        shouldThrow<BadRequestException> {
            call.run { CallParameterId.run { idOrFail() } }
        }
    }

    test("JwtId.getOrNull returns user id from KodexPrincipal") {
        val id = UUID.randomUUID()
        val principal = mockk<KodexPrincipal>()
        every { principal.userId } returns id
        val call = mockk<ApplicationCall>()
        every { call.kodex } returns principal

        KodexId.getOrNull(call) shouldBe id
    }

    test("JwtId.getOrNull returns null when principal missing") {
        val call = mockk<ApplicationCall>()
        every { call.kodex } returns null

        KodexId.getOrNull(call) shouldBe null
    }
})
