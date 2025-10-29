package com.mustafadakhel.kodex.extension

/** Strategy for handling hook failures. */
public enum class HookFailureStrategy {
    /** Stop on first failure and throw exception. */
    FAIL_FAST,

    /** Execute all hooks and collect failures. */
    COLLECT_ERRORS,

    /** Skip failed hooks and continue. */
    SKIP_FAILED
}

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

public data class HookFailure(
    val hookName: String,
    val exception: Throwable
)
