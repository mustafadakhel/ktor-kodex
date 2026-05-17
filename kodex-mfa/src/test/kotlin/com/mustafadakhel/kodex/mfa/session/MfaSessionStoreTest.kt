package com.mustafadakhel.kodex.mfa.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MfaSessionStoreTest : FunSpec({

    test("should create and retrieve session") {
        val store = MfaSessionStore()
        val userId = UUID.randomUUID()

        val session = store.createSession(userId, "127.0.0.1", "TestAgent")

        session.sessionId shouldNotBe null
        session.userId shouldBe userId
        session.ipAddress shouldBe "127.0.0.1"
        session.userAgent shouldBe "TestAgent"

        val retrieved = store.getSession(session.sessionId)
        retrieved shouldNotBe null
        retrieved?.sessionId shouldBe session.sessionId
    }

    test("should return null for non-existent session") {
        val store = MfaSessionStore()

        val session = store.getSession("non-existent-id")
        session shouldBe null
    }

    test("should remove session") {
        val store = MfaSessionStore()
        val userId = UUID.randomUUID()

        val session = store.createSession(userId, null, null)
        store.getSession(session.sessionId) shouldNotBe null

        store.removeSession(session.sessionId)
        store.getSession(session.sessionId) shouldBe null
    }

    test("should expire sessions after timeout") {
        val store = MfaSessionStore(sessionExpiration = 100.milliseconds)
        val userId = UUID.randomUUID()

        val session = store.createSession(userId, null, null)
        store.getSession(session.sessionId) shouldNotBe null

        delay(150)

        store.getSession(session.sessionId) shouldBe null
    }

    test("should enforce maximum active sessions per user") {
        val store = MfaSessionStore(maxActiveSessions = 2)
        val userId = UUID.randomUUID()

        val session1 = store.createSession(userId, null, null)
        delay(10)
        val session2 = store.createSession(userId, null, null)

        store.getSession(session1.sessionId) shouldNotBe null
        store.getSession(session2.sessionId) shouldNotBe null

        delay(10)
        val session3 = store.createSession(userId, null, null)

        val remainingSessions = listOf(
            store.getSession(session1.sessionId),
            store.getSession(session2.sessionId),
            store.getSession(session3.sessionId)
        ).filterNotNull()

        remainingSessions.size shouldBe 2
        store.getSession(session3.sessionId) shouldNotBe null
    }

    test("should allow multiple sessions for different users") {
        val store = MfaSessionStore()
        val userId1 = UUID.randomUUID()
        val userId2 = UUID.randomUUID()

        val session1 = store.createSession(userId1, null, null)
        val session2 = store.createSession(userId2, null, null)

        store.getSession(session1.sessionId) shouldNotBe null
        store.getSession(session2.sessionId) shouldNotBe null
    }

    test("should store IP address and user agent") {
        val store = MfaSessionStore()
        val userId = UUID.randomUUID()

        val session = store.createSession(
            userId = userId,
            ipAddress = "192.168.1.1",
            userAgent = "Mozilla/5.0"
        )

        val retrieved = store.getSession(session.sessionId)
        retrieved?.ipAddress shouldBe "192.168.1.1"
        retrieved?.userAgent shouldBe "Mozilla/5.0"
    }
})
