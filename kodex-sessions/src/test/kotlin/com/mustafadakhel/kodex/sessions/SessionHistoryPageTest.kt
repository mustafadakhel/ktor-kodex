package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.sessions.model.SessionHistoryEntry
import com.mustafadakhel.kodex.sessions.model.SessionHistoryPage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * Session history pagination tests.
 */
class SessionHistoryPageTest : StringSpec({

    fun createEntry(userId: UUID = UUID.randomUUID()): SessionHistoryEntry {
        val now = Clock.System.now()
        return SessionHistoryEntry(
            id = UUID.randomUUID(),
            realmId = "test-realm",
            userId = userId,
            sessionId = UUID.randomUUID(),
            deviceName = "Chrome on Windows",
            ipAddress = "192.168.1.1",
            location = "New York, USA",
            loginAt = now,
            logoutAt = now,
            endReason = "user_revoked"
        )
    }

    "SessionHistoryPage should correctly calculate hasMore when more entries exist" {
        val entries = (1..20).map { createEntry() }
        val page = SessionHistoryPage.create(
            entries = entries,
            totalCount = 100,
            offset = 0,
            limit = 20
        )

        page.entries shouldHaveSize 20
        page.totalCount shouldBe 100
        page.offset shouldBe 0
        page.limit shouldBe 20
        page.hasMore.shouldBeTrue()
    }

    "SessionHistoryPage should correctly calculate hasMore when no more entries" {
        val entries = (1..20).map { createEntry() }
        val page = SessionHistoryPage.create(
            entries = entries,
            totalCount = 100,
            offset = 80,
            limit = 20
        )

        page.entries shouldHaveSize 20
        page.totalCount shouldBe 100
        page.offset shouldBe 80
        page.hasMore.shouldBeFalse() // 80 + 20 = 100, no more
    }

    "SessionHistoryPage should handle partial last page" {
        val entries = (1..10).map { createEntry() }
        val page = SessionHistoryPage.create(
            entries = entries,
            totalCount = 50,
            offset = 40,
            limit = 20
        )

        page.entries shouldHaveSize 10
        page.totalCount shouldBe 50
        page.offset shouldBe 40
        page.hasMore.shouldBeFalse() // 40 + 10 = 50, no more
    }

    "SessionHistoryPage should handle empty entries" {
        val page = SessionHistoryPage.create(
            entries = emptyList(),
            totalCount = 0,
            offset = 0,
            limit = 20
        )

        page.entries.shouldBeEmpty()
        page.totalCount shouldBe 0
        page.hasMore.shouldBeFalse()
    }

    "SessionHistoryPage should handle single page of results" {
        val entries = (1..5).map { createEntry() }
        val page = SessionHistoryPage.create(
            entries = entries,
            totalCount = 5,
            offset = 0,
            limit = 20
        )

        page.entries shouldHaveSize 5
        page.totalCount shouldBe 5
        page.hasMore.shouldBeFalse()
    }

    "SessionHistoryPage should handle offset at exact boundary" {
        val entries = (1..20).map { createEntry() }
        val page = SessionHistoryPage.create(
            entries = entries,
            totalCount = 40,
            offset = 20,
            limit = 20
        )

        page.entries shouldHaveSize 20
        page.hasMore.shouldBeFalse() // 20 + 20 = 40, exactly at end
    }
})
