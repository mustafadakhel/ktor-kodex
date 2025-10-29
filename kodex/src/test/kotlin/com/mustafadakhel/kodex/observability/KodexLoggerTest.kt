package com.mustafadakhel.kodex.observability

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

class KodexLoggerTest : DescribeSpec({

    beforeEach {
        // Clear MDC before each test
        MDC.clear()
    }

    afterEach {
        // Clear MDC after each test
        MDC.clear()
    }

    describe("KodexLogger") {
        describe("logger() inline function") {
            it("should create logger for specified class") {
                val logger = KodexLogger.logger<KodexLoggerTest>()

                logger shouldNotBe null
                logger.name shouldBe "com.mustafadakhel.kodex.observability.KodexLoggerTest"
            }

            it("should create different loggers for different classes") {
                val logger1 = KodexLogger.logger<KodexLoggerTest>()
                val logger2 = KodexLogger.logger<LogContext>()

                logger1.name shouldNotBe logger2.name
            }
        }

        describe("logger(name) function") {
            it("should create logger with specified name") {
                val logger = KodexLogger.logger("custom.logger")

                logger shouldNotBe null
                logger.name shouldBe "custom.logger"
            }

            it("should create logger with package-style name") {
                val logger = KodexLogger.logger("com.example.test")

                logger.name shouldBe "com.example.test"
            }
        }
    }

    describe("LogContext constants") {
        it("should have REALM_ID constant") {
            LogContext.REALM_ID shouldBe "realm_id"
        }

        it("should have USER_ID constant") {
            LogContext.USER_ID shouldBe "user_id"
        }

        it("should have SESSION_ID constant") {
            LogContext.SESSION_ID shouldBe "session_id"
        }

        it("should have OPERATION constant") {
            LogContext.OPERATION shouldBe "operation"
        }

        it("should have CORRELATION_ID constant") {
            LogContext.CORRELATION_ID shouldBe "correlation_id"
        }
    }

    describe("withLoggingContext") {
        it("should set realm ID in MDC") {
            withLoggingContext(realmId = "test-realm") {
                MDC.get(LogContext.REALM_ID) shouldBe "test-realm"
            }
        }

        it("should clear realm ID after block") {
            withLoggingContext(realmId = "test-realm") {
                MDC.get(LogContext.REALM_ID) shouldBe "test-realm"
            }
            MDC.get(LogContext.REALM_ID) shouldBe null
        }

        it("should set user ID in MDC") {
            val userId = UUID.randomUUID()
            withLoggingContext(userId = userId) {
                MDC.get(LogContext.USER_ID) shouldBe userId.toString()
            }
        }

        it("should clear user ID after block") {
            val userId = UUID.randomUUID()
            withLoggingContext(userId = userId) {
                MDC.get(LogContext.USER_ID) shouldBe userId.toString()
            }
            MDC.get(LogContext.USER_ID) shouldBe null
        }

        it("should set session ID in MDC") {
            val sessionId = UUID.randomUUID()
            withLoggingContext(sessionId = sessionId) {
                MDC.get(LogContext.SESSION_ID) shouldBe sessionId.toString()
            }
        }

        it("should set operation in MDC") {
            withLoggingContext(operation = "authenticate") {
                MDC.get(LogContext.OPERATION) shouldBe "authenticate"
            }
        }

        it("should set correlation ID in MDC") {
            withLoggingContext(correlationId = "corr-123") {
                MDC.get(LogContext.CORRELATION_ID) shouldBe "corr-123"
            }
        }

        it("should set multiple values in MDC") {
            val userId = UUID.randomUUID()
            withLoggingContext(
                realmId = "realm-1",
                userId = userId,
                operation = "create_user"
            ) {
                MDC.get(LogContext.REALM_ID) shouldBe "realm-1"
                MDC.get(LogContext.USER_ID) shouldBe userId.toString()
                MDC.get(LogContext.OPERATION) shouldBe "create_user"
            }
        }

        it("should clear all values after block") {
            val userId = UUID.randomUUID()
            withLoggingContext(
                realmId = "realm-1",
                userId = userId,
                operation = "test"
            ) {}

            MDC.get(LogContext.REALM_ID) shouldBe null
            MDC.get(LogContext.USER_ID) shouldBe null
            MDC.get(LogContext.OPERATION) shouldBe null
        }

        it("should return block result") {
            val result = withLoggingContext(realmId = "test") {
                "success"
            }

            result shouldBe "success"
        }

        it("should handle null parameters") {
            withLoggingContext(
                realmId = null,
                userId = null,
                sessionId = null,
                operation = null,
                correlationId = null
            ) {
                MDC.get(LogContext.REALM_ID) shouldBe null
                MDC.get(LogContext.USER_ID) shouldBe null
                MDC.get(LogContext.SESSION_ID) shouldBe null
                MDC.get(LogContext.OPERATION) shouldBe null
                MDC.get(LogContext.CORRELATION_ID) shouldBe null
            }
        }

        it("should restore previous context values") {
            MDC.put(LogContext.REALM_ID, "original-realm")

            withLoggingContext(realmId = "temp-realm") {
                MDC.get(LogContext.REALM_ID) shouldBe "temp-realm"
            }

            MDC.get(LogContext.REALM_ID) shouldBe "original-realm"
        }

        it("should handle nested contexts") {
            withLoggingContext(realmId = "outer-realm") {
                MDC.get(LogContext.REALM_ID) shouldBe "outer-realm"

                withLoggingContext(realmId = "inner-realm") {
                    MDC.get(LogContext.REALM_ID) shouldBe "inner-realm"
                }

                MDC.get(LogContext.REALM_ID) shouldBe "outer-realm"
            }

            MDC.get(LogContext.REALM_ID) shouldBe null
        }

        it("should clean up even if block throws exception") {
            try {
                withLoggingContext(realmId = "test-realm") {
                    throw RuntimeException("Test error")
                }
            } catch (e: RuntimeException) {
                // Expected
            }

            MDC.get(LogContext.REALM_ID) shouldBe null
        }

        it("should set all five context fields") {
            val userId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()

            withLoggingContext(
                realmId = "realm",
                userId = userId,
                sessionId = sessionId,
                operation = "op",
                correlationId = "corr"
            ) {
                MDC.get(LogContext.REALM_ID) shouldBe "realm"
                MDC.get(LogContext.USER_ID) shouldBe userId.toString()
                MDC.get(LogContext.SESSION_ID) shouldBe sessionId.toString()
                MDC.get(LogContext.OPERATION) shouldBe "op"
                MDC.get(LogContext.CORRELATION_ID) shouldBe "corr"
            }
        }
    }

    describe("Logger extensions") {
        val logger = LoggerFactory.getLogger("test")

        describe("logAuthSuccess") {
            it("should log with email identifier") {
                val userId = UUID.randomUUID()
                logger.logAuthSuccess("test@example.com", null, userId, "test-realm")
                // No assertion - just verify it doesn't throw
            }

            it("should log with phone identifier") {
                val userId = UUID.randomUUID()
                logger.logAuthSuccess(null, "+1234567890", userId, "test-realm")
                // No assertion - just verify it doesn't throw
            }

            it("should prefer email over phone") {
                val userId = UUID.randomUUID()
                logger.logAuthSuccess("test@example.com", "+1234567890", userId, "test-realm")
                // No assertion - just verify it doesn't throw
            }
        }

        describe("logAuthFailure") {
            it("should log with email identifier") {
                logger.logAuthFailure("test@example.com", null, "Invalid password", "test-realm")
                // No assertion - just verify it doesn't throw
            }

            it("should log with phone identifier") {
                logger.logAuthFailure(null, "+1234567890", "Invalid password", "test-realm")
                // No assertion - just verify it doesn't throw
            }

            it("should log with reason") {
                logger.logAuthFailure("user@test.com", null, "Account locked", "test-realm")
                // No assertion - just verify it doesn't throw
            }
        }

        describe("logTokenOperation") {
            it("should log successful token operation") {
                logger.logTokenOperation("generate", "access", true, "test-realm")
                // No assertion - just verify it doesn't throw
            }

            it("should log failed token operation") {
                logger.logTokenOperation("validate", "refresh", false, "test-realm")
                // No assertion - just verify it doesn't throw
            }

            it("should log different operations") {
                logger.logTokenOperation("revoke", "access", true, "test-realm")
                logger.logTokenOperation("rotate", "refresh", true, "test-realm")
                // No assertion - just verify it doesn't throw
            }
        }

        describe("logAccountLockout") {
            it("should log account locked") {
                val userId = UUID.randomUUID()
                logger.logAccountLockout(userId, true, "test-realm")
                // No assertion - just verify it doesn't throw
            }

            it("should log account unlocked") {
                val userId = UUID.randomUUID()
                logger.logAccountLockout(userId, false, "test-realm")
                // No assertion - just verify it doesn't throw
            }
        }

        describe("logValidationFailure") {
            it("should log validation failure") {
                logger.logValidationFailure("email", "Invalid format", "test-realm")
                // No assertion - just verify it doesn't throw
            }

            it("should log different field failures") {
                logger.logValidationFailure("password", "Too short", "test-realm")
                logger.logValidationFailure("phone", "Invalid country code", "test-realm")
                // No assertion - just verify it doesn't throw
            }
        }
    }
})
