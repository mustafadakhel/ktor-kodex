package com.mustafadakhel.kodex.extension

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class HookFailureStrategyTest : DescribeSpec({

    describe("HookFailureStrategy enum") {
        it("should have FAIL_FAST value") {
            HookFailureStrategy.FAIL_FAST shouldBe HookFailureStrategy.FAIL_FAST
        }

        it("should have COLLECT_ERRORS value") {
            HookFailureStrategy.COLLECT_ERRORS shouldBe HookFailureStrategy.COLLECT_ERRORS
        }

        it("should have SKIP_FAILED value") {
            HookFailureStrategy.SKIP_FAILED shouldBe HookFailureStrategy.SKIP_FAILED
        }

        it("should have exactly three values") {
            HookFailureStrategy.entries.size shouldBe 3
        }

        it("should contain all expected values") {
            val strategies = HookFailureStrategy.entries
            strategies shouldContainExactly listOf(
                HookFailureStrategy.FAIL_FAST,
                HookFailureStrategy.COLLECT_ERRORS,
                HookFailureStrategy.SKIP_FAILED
            )
        }

        it("should support valueOf") {
            HookFailureStrategy.valueOf("FAIL_FAST") shouldBe HookFailureStrategy.FAIL_FAST
            HookFailureStrategy.valueOf("COLLECT_ERRORS") shouldBe HookFailureStrategy.COLLECT_ERRORS
            HookFailureStrategy.valueOf("SKIP_FAILED") shouldBe HookFailureStrategy.SKIP_FAILED
        }

        it("should have correct enum names") {
            HookFailureStrategy.FAIL_FAST.name shouldBe "FAIL_FAST"
            HookFailureStrategy.COLLECT_ERRORS.name shouldBe "COLLECT_ERRORS"
            HookFailureStrategy.SKIP_FAILED.name shouldBe "SKIP_FAILED"
        }

        it("should preserve order") {
            val entries = HookFailureStrategy.entries
            entries[0] shouldBe HookFailureStrategy.FAIL_FAST
            entries[1] shouldBe HookFailureStrategy.COLLECT_ERRORS
            entries[2] shouldBe HookFailureStrategy.SKIP_FAILED
        }
    }

    describe("HookFailure data class") {
        val testException = RuntimeException("Test failure")

        it("should store hook name and exception") {
            val failure = HookFailure("testHook", testException)

            failure.hookName shouldBe "testHook"
            failure.exception shouldBe testException
        }

        it("should support data class equality") {
            val failure1 = HookFailure("hook1", testException)
            val failure2 = HookFailure("hook1", testException)

            (failure1 == failure2) shouldBe true
        }

        it("should have different equality for different hook names") {
            val failure1 = HookFailure("hook1", testException)
            val failure2 = HookFailure("hook2", testException)

            (failure1 == failure2) shouldBe false
        }

        it("should support data class copy") {
            val original = HookFailure("originalHook", testException)
            val newException = IllegalStateException("New error")
            val copy = original.copy(exception = newException)

            copy.hookName shouldBe "originalHook"
            copy.exception shouldBe newException
        }

        it("should handle different exception types") {
            val illegalArgException = IllegalArgumentException("Invalid arg")
            val failure = HookFailure("validationHook", illegalArgException)

            failure.exception shouldBe illegalArgException
            failure.exception.message shouldBe "Invalid arg"
        }
    }

    describe("HookExecutionException") {
        val exception1 = RuntimeException("Error 1")
        val exception2 = IllegalStateException("Error 2")
        val failures = listOf(
            HookFailure("beforeUserCreate", exception1),
            HookFailure("beforeLogin", exception2)
        )

        it("should store message and failures") {
            val hookException = HookExecutionException("Multiple hooks failed", failures)

            hookException.message shouldBe "Multiple hooks failed"
            hookException.failures shouldBe failures
        }

        it("should include failures in toString") {
            val hookException = HookExecutionException("Hook execution failed", failures)
            val string = hookException.toString()

            string shouldContain "Hook execution failed"
            string shouldContain "beforeUserCreate"
            string shouldContain "Error 1"
            string shouldContain "beforeLogin"
            string shouldContain "Error 2"
        }

        it("should format single failure") {
            val singleFailure = listOf(HookFailure("myHook", RuntimeException("Single error")))
            val hookException = HookExecutionException("One hook failed", singleFailure)
            val string = hookException.toString()

            string shouldContain "One hook failed"
            string shouldContain "myHook: Single error"
        }

        it("should handle empty failures list") {
            val hookException = HookExecutionException("No failures", emptyList())

            hookException.failures.size shouldBe 0
            hookException.message shouldBe "No failures"
        }

        it("should be instance of Exception") {
            val hookException = HookExecutionException("Test", failures)
            val isException = hookException is Exception

            isException shouldBe true
        }

        it("should preserve exception hierarchy") {
            val hookException = HookExecutionException("Test", failures)
            val isThrowable = hookException is Throwable

            isThrowable shouldBe true
        }

        it("should format multiple failures correctly") {
            val multipleFailures = listOf(
                HookFailure("hook1", RuntimeException("Error 1")),
                HookFailure("hook2", IllegalArgumentException("Error 2")),
                HookFailure("hook3", IllegalStateException("Error 3"))
            )
            val hookException = HookExecutionException("Three hooks failed", multipleFailures)
            val string = hookException.toString()

            string shouldContain "Three hooks failed"
            string shouldContain "hook1: Error 1"
            string shouldContain "hook2: Error 2"
            string shouldContain "hook3: Error 3"
        }

        it("should handle null exception messages") {
            val nullMessageException = RuntimeException(null as String?)
            val failure = listOf(HookFailure("testHook", nullMessageException))
            val hookException = HookExecutionException("Test", failure)
            val string = hookException.toString()

            string shouldContain "testHook: null"
        }

        it("should access failures property") {
            val hookException = HookExecutionException("Test", failures)

            hookException.failures.size shouldBe 2
            hookException.failures[0].hookName shouldBe "beforeUserCreate"
            hookException.failures[1].hookName shouldBe "beforeLogin"
        }

        it("should maintain failure order") {
            val orderedFailures = listOf(
                HookFailure("first", RuntimeException("1")),
                HookFailure("second", RuntimeException("2")),
                HookFailure("third", RuntimeException("3"))
            )
            val hookException = HookExecutionException("Ordered", orderedFailures)

            hookException.failures[0].hookName shouldBe "first"
            hookException.failures[1].hookName shouldBe "second"
            hookException.failures[2].hookName shouldBe "third"
        }
    }
})
