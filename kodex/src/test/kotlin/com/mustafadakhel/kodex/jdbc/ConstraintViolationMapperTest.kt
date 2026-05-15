package com.mustafadakhel.kodex.jdbc

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException

class ConstraintViolationMapperTest {

    @Test
    fun `detects matching index name case-insensitively`() {
        val ex = SQLException("Unique index or primary key violation: \"IDX_USERS_EMAIL_REALM\"", "23505")
        val result = ConstraintViolationMapper.detectDuplicateIndex(ex, "idx_users_email_realm", "idx_users_phone_realm")
        result shouldBe "idx_users_email_realm"
    }

    @Test
    fun `returns null for non-matching index name`() {
        val ex = SQLException("Unique index or primary key violation: \"IDX_OTHER\"", "23505")
        val result = ConstraintViolationMapper.detectDuplicateIndex(ex, "idx_users_email_realm")
        result.shouldBeNull()
    }

    @Test
    fun `returns null for non-23 sqlState`() {
        val ex = SQLException("Some error", "42000")
        val result = ConstraintViolationMapper.detectDuplicateIndex(ex, "idx_users_email_realm")
        result.shouldBeNull()
    }

    @Test
    fun `returns null for null sqlState`() {
        val ex = SQLException("Some error")
        val result = ConstraintViolationMapper.detectDuplicateIndex(ex, "idx_users_email_realm")
        result.shouldBeNull()
    }
}
