package com.mustafadakhel.kodex.extension

/**
 * Strategy for handling failures when executing extension hooks.
 *
 * Determines what happens when a hook throws an exception during execution.
 */
public enum class HookFailureStrategy {
    /**
     * Stop immediately on first hook failure and propagate the exception.
     *
     * This is the default strategy and provides fail-fast behavior:
     * - First hook that throws stops the entire chain
     * - Subsequent hooks don't execute
     * - Exception is propagated to the caller
     * - Clear error messages for debugging
     *
     * Use when: You need strict validation and any hook failure should abort the operation.
     */
    FAIL_FAST,

    /**
     * Continue executing all hooks even if some fail, then throw aggregated exceptions.
     *
     * This strategy runs all hooks and collects all failures:
     * - All hooks execute regardless of failures
     * - Exceptions are collected in a HookExecutionException
     * - Final exception contains all failures
     * - Useful for collecting all validation errors
     *
     * Use when: You want to show users ALL validation errors, not just the first one.
     */
    COLLECT_ERRORS,

    /**
     * Skip failed hooks and continue executing remaining hooks.
     *
     * This strategy treats hook failures as non-fatal:
     * - Failed hooks are logged but don't stop execution
     * - Subsequent hooks continue normally
     * - No exception is thrown to the caller
     * - Useful for optional hooks (logging, metrics)
     *
     * Use when: Hooks are optional and failures shouldn't affect the main operation.
     */
    SKIP_FAILED
}

/**
 * Exception thrown when using COLLECT_ERRORS strategy and multiple hooks fail.
 *
 * Contains all exceptions thrown by failed hooks for comprehensive error reporting.
 */
public class HookExecutionException(
    message: String,
    public val failures: List<HookFailure>
) : Exception(message) {
    override fun toString(): String {
        val failureDetails = failures.joinToString("\n") { failure ->
            "  - ${failure.hookName}: ${failure.exception.message}"
        }
        return "$message\n$failureDetails"
    }
}

/**
 * Represents a single hook failure with context.
 */
public data class HookFailure(
    val hookName: String,
    val exception: Throwable
)
