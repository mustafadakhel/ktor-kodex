package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.audit.AuditConfig
import io.ktor.utils.io.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * DSL scope for configuring audit logging.
 *
 * Example usage:
 * ```kotlin
 * install(Kodex) {
 *     audit {
 *         enabled = true
 *         queueCapacity = 10000
 *         batchSize = 100
 *         flushInterval = 5.seconds
 *         retentionPeriod = 90.days
 *     }
 * }
 * ```
 */
@KtorDsl
public class AuditConfigScope internal constructor() {

    /**
     * Whether audit logging is enabled.
     *
     * Default: true
     *
     * When disabled, audit events are not logged at all (zero overhead).
     */
    public var enabled: Boolean = true

    /**
     * Maximum number of events to hold in the in-memory queue.
     *
     * Default: 10,000
     *
     * If the queue is full, new events will be dropped (non-blocking behavior).
     * Increase this value for high-traffic systems with bursty audit patterns.
     */
    public var queueCapacity: Int = 10_000

    /**
     * Number of events to write in a single database batch.
     *
     * Default: 100
     *
     * Higher values improve write performance but increase memory usage.
     * Lower values reduce latency for event queries at the cost of write throughput.
     */
    public var batchSize: Int = 100

    /**
     * Maximum time to wait before flushing a partial batch.
     *
     * Default: 5 seconds
     *
     * Ensures events are written even if the batch size isn't reached.
     * Lower values reduce query latency but may decrease write efficiency.
     */
    public var flushInterval: Duration = 5.seconds

    /**
     * How long to retain audit logs before automatic cleanup.
     *
     * Default: 90 days
     *
     * Set to null to retain logs indefinitely.
     * Note: Automatic cleanup requires a background job (future enhancement).
     *
     * Common retention periods:
     * - GDPR: 30-90 days for most data
     * - SOC 2: 1 year minimum
     * - HIPAA: 6 years
     * - PCI-DSS: 1 year, 3 months readily accessible
     */
    public var retentionPeriod: Duration? = 90.days

    /**
     * Build the immutable configuration object.
     */
    internal fun build(): AuditConfig = AuditConfig(
        enabled = enabled,
        queueCapacity = queueCapacity,
        batchSize = batchSize,
        flushInterval = flushInterval,
        retentionPeriod = retentionPeriod
    )
}
