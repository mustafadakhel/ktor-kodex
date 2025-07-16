package com.mustafadakhel.kodex.routes.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.util.*

private object TestIdScope : IdScope {
    val expectedId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000123")
    var lastCall: ApplicationCall? = null
    override fun ApplicationCall.idOrFail(): UUID = getOrNull(this)
    override fun getOrNull(call: ApplicationCall): UUID {
        lastCall = call
        return expectedId
    }

    override fun idNotFound(): Nothing = error("id not found")
}

class AuthorizedRouteTest : StringSpec({
    "authorizedGet should pass id from scope" {
        TestIdScope.lastCall = null
        testApplication {
            application {
                routing {
                    authorizedRoute("/get", TestIdScope) {
                        get { id ->
                            call.respondText(id.toString())
                        }
                    }
                }
            }
            val response = client.get("/get")
            response.bodyAsText() shouldBe TestIdScope.expectedId.toString()
            TestIdScope.lastCall shouldNotBe null
        }
    }

    "authorizedPost should pass id from scope" {
        TestIdScope.lastCall = null
        testApplication {
            application {
                routing {
                    authorizedRoute("/post", TestIdScope) {
                        post { id ->
                            call.respondText(id.toString())
                        }
                    }
                }
            }
            val response = client.post("/post")
            response.bodyAsText() shouldBe TestIdScope.expectedId.toString()
        }
    }

    "authorizedRoute should pass id to nested route" {
        TestIdScope.lastCall = null
        testApplication {
            application {
                routing {
                    authorizedRoute("/nested", TestIdScope) {
                        route("/child") {
                            get { id ->
                                call.respondText(id.toString())
                            }
                        }
                    }
                }
            }
            val response = client.get("/nested/child")
            response.bodyAsText() shouldBe TestIdScope.expectedId.toString()
        }
    }
})
