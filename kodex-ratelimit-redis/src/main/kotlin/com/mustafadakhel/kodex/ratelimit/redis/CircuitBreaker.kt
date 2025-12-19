package com.mustafadakhel.kodex.ratelimit.redis

import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * Circuit breaker for Redis operations.
 *
 * States: CLOSED (normal), OPEN (failed), HALF_OPEN (testing recovery).
 */
public class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val timeout: Duration,
    private val halfOpenAttempts: Int = 3
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(timeout.isPositive()) { "timeout must be positive" }
        require(halfOpenAttempts > 0) { "halfOpenAttempts must be positive" }
    }

    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state = AtomicReference(State.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val lastFailureTime = AtomicReference<Instant?>(null)

    public val isOpen: Boolean
        get() {
            val currentState = state.get()
            if (currentState == State.OPEN) {
                checkForRecovery()
            }
            return state.get() == State.OPEN
        }

    /**
     * Check if circuit is closed (Redis available).
     */
    public val isClosed: Boolean
        get() = state.get() == State.CLOSED

    /**
     * Check if circuit is half-open (testing recovery).
     */
    public val isHalfOpen: Boolean
        get() = state.get() == State.HALF_OPEN

    /**
     * Record a successful Redis operation.
     */
    public fun recordSuccess() {
        val currentState = state.get()

        when (currentState) {
            State.CLOSED -> {
                failureCount.set(0)
            }
            State.HALF_OPEN -> {
                val successes = successCount.incrementAndGet()
                if (successes >= halfOpenAttempts) {
                    state.set(State.CLOSED)
                    failureCount.set(0)
                    successCount.set(0)
                }
            }
            State.OPEN -> {
                // Shouldn't happen, but reset if it does
            }
        }
    }

    /**
     * Record a failed Redis operation.
     */
    public fun recordFailure() {
        lastFailureTime.set(CurrentKotlinInstant)

        val currentState = state.get()
        when (currentState) {
            State.CLOSED -> {
                val failures = failureCount.incrementAndGet()
                if (failures >= failureThreshold) {
                    state.set(State.OPEN)
                }
            }
            State.HALF_OPEN -> {
                state.set(State.OPEN)
                successCount.set(0)
            }
            State.OPEN -> {
                // Already open
            }
        }
    }

    /**
     * Get current circuit state as string.
     */
    public fun getState(): String = state.get().name

    /**
     * Get current failure count.
     */
    public fun getFailureCount(): Int = failureCount.get()

    private fun checkForRecovery() {
        val currentState = state.get()
        if (currentState != State.OPEN) return

        val lastFailure = lastFailureTime.get() ?: return
        val now = CurrentKotlinInstant

        if (now - lastFailure >= timeout) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN)
            successCount.set(0)
        }
    }
}
