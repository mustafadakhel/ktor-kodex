package com.mustafadakhel.kodex.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.MDC
import java.util.*

class StructuredLoggingTest : FunSpec({

    afterEach {
        // Clean up MDC after each test
        MDC.clear()
    }

    context("Logging Context") {

        test("should set realm ID in MDC") {
            withLoggingContext(realmId = "test-realm") {
                MDC.get(LogContext.REALM_ID) shouldBe "test-realm"
            }

            // Should be cleared after block
            MDC.get(LogContext.REALM_ID) shouldBe null
        }

        test("should set user ID in MDC") {
            val userId = UUID.randomUUID()
            withLoggingContext(userId = userId) {
                MDC.get(LogContext.USER_ID) shouldBe userId.toString()
            }

            // Should be cleared after block
            MDC.get(LogContext.USER_ID) shouldBe null
        }

        test("should set session ID in MDC") {
            val sessionId = UUID.randomUUID()
            withLoggingContext(sessionId = sessionId) {
                MDC.get(LogContext.SESSION_ID) shouldBe sessionId.toString()
            }

            // Should be cleared after block
            MDC.get(LogContext.SESSION_ID) shouldBe null
        }

        test("should set operation in MDC") {
            withLoggingContext(operation = "authenticate") {
                MDC.get(LogContext.OPERATION) shouldBe "authenticate"
            }

            // Should be cleared after block
            MDC.get(LogContext.OPERATION) shouldBe null
        }

        test("should set correlation ID in MDC") {
            withLoggingContext(correlationId = "req-12345") {
                MDC.get(LogContext.CORRELATION_ID) shouldBe "req-12345"
            }

            // Should be cleared after block
            MDC.get(LogContext.CORRELATION_ID) shouldBe null
        }

        test("should set multiple context values") {
            val userId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()

            withLoggingContext(
                realmId = "test-realm",
                userId = userId,
                sessionId = sessionId,
                operation = "login",
                correlationId = "req-123"
            ) {
                MDC.get(LogContext.REALM_ID) shouldBe "test-realm"
                MDC.get(LogContext.USER_ID) shouldBe userId.toString()
                MDC.get(LogContext.SESSION_ID) shouldBe sessionId.toString()
                MDC.get(LogContext.OPERATION) shouldBe "login"
                MDC.get(LogContext.CORRELATION_ID) shouldBe "req-123"
            }

            // All should be cleared after block
            MDC.get(LogContext.REALM_ID) shouldBe null
            MDC.get(LogContext.USER_ID) shouldBe null
            MDC.get(LogContext.SESSION_ID) shouldBe null
            MDC.get(LogContext.OPERATION) shouldBe null
            MDC.get(LogContext.CORRELATION_ID) shouldBe null
        }

        test("should restore previous context values") {
            // Set initial context
            MDC.put(LogContext.REALM_ID, "realm1")
            MDC.put(LogContext.OPERATION, "op1")

            withLoggingContext(realmId = "realm2", operation = "op2") {
                MDC.get(LogContext.REALM_ID) shouldBe "realm2"
                MDC.get(LogContext.OPERATION) shouldBe "op2"
            }

            // Should restore previous values
            MDC.get(LogContext.REALM_ID) shouldBe "realm1"
            MDC.get(LogContext.OPERATION) shouldBe "op1"
        }

        test("should return value from block") {
            val result = withLoggingContext(realmId = "test") {
                "test-value"
            }

            result shouldBe "test-value"
        }

        test("should handle exceptions and still clean up context") {
            try {
                withLoggingContext(realmId = "test") {
                    throw RuntimeException("Test error")
                }
            } catch (e: RuntimeException) {
                // Expected
            }

            // Context should still be cleaned up
            MDC.get(LogContext.REALM_ID) shouldBe null
        }

        test("should support nested context blocks") {
            withLoggingContext(realmId = "outer", operation = "op1") {
                MDC.get(LogContext.REALM_ID) shouldBe "outer"
                MDC.get(LogContext.OPERATION) shouldBe "op1"

                withLoggingContext(operation = "op2") {
                    MDC.get(LogContext.REALM_ID) shouldBe "outer"
                    MDC.get(LogContext.OPERATION) shouldBe "op2"
                }

                // Inner context changes should be reverted
                MDC.get(LogContext.REALM_ID) shouldBe "outer"
                MDC.get(LogContext.OPERATION) shouldBe "op1"
            }

            // Outer context should be cleaned up
            MDC.get(LogContext.REALM_ID) shouldBe null
            MDC.get(LogContext.OPERATION) shouldBe null
        }
    }

    context("Logger Creation") {

        test("should create logger for class") {
            val logger = KodexLogger.logger<StructuredLoggingTest>()
            logger.name shouldBe "com.mustafadakhel.kodex.observability.StructuredLoggingTest"
        }

        test("should create logger with name") {
            val logger = KodexLogger.logger("com.example.MyClass")
            logger.name shouldBe "com.example.MyClass"
        }
    }

    context("Log Context Constants") {

        test("should have correct MDC key names") {
            LogContext.REALM_ID shouldBe "realm_id"
            LogContext.USER_ID shouldBe "user_id"
            LogContext.SESSION_ID shouldBe "session_id"
            LogContext.OPERATION shouldBe "operation"
            LogContext.CORRELATION_ID shouldBe "correlation_id"
        }
    }
})
