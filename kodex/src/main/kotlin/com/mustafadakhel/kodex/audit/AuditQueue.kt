package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.model.database.AuditLogDao
import com.mustafadakhel.kodex.model.database.AuditLogs
import com.mustafadakhel.kodex.util.exposedTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.toJavaInstant
import java.sql.Timestamp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Async queue for non-blocking audit event processing.
 *
 * Design principles:
 * - Non-blocking: Audit logging never delays business logic
 * - Batched: Write multiple events in a single transaction for performance
 * - Resilient: Failed writes don't crash the application
 * - Bounded: Queue has capacity limits to prevent memory issues
 */
internal interface AuditQueue {
    /**
     * Enqueue an event for async processing.
     *
     * @param event The audit event to queue
     */
    suspend fun enqueue(event: AuditEvent)

    /**
     * Start the background processor.
     */
    fun start()

    /**
     * Stop the background processor and flush remaining events.
     */
    fun stop()
}

/**
 * In-memory queue implementation with coroutine-based batch processing.
 *
 * For production scale with multiple instances, consider:
 * - Redis-backed queue for distributed processing
 * - Kafka for high-throughput event streaming
 * - RabbitMQ for reliable message delivery
 *
 * @property writer The database writer for persisting events
 * @property queueCapacity Maximum number of events to hold in memory
 * @property batchSize Number of events to write in a single batch
 * @property flushInterval Maximum time to wait before flushing a partial batch
 */
internal class InMemoryAuditQueue(
    private val writer: AuditWriter,
    private val queueCapacity: Int = 10_000,
    private val batchSize: Int = 100,
    private val flushInterval: Duration = 5.seconds
) : AuditQueue {

    private val queue = Channel<AuditEvent>(queueCapacity)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processorJob: Job? = null

    override suspend fun enqueue(event: AuditEvent) {
        try {
            // Use trySend for non-blocking behavior
            // If queue is full, log error but don't block
            val result = queue.trySend(event)
            if (result.isFailure) {
                // In production, this should go to error tracking (Sentry, Datadog, etc.)
                System.err.println("Audit queue full - event dropped: ${event.eventType}")
            }
        } catch (e: Exception) {
            // Never throw exceptions from audit logging
            System.err.println("Failed to enqueue audit event: ${e.message}")
        }
    }

    override fun start() {
        processorJob = scope.launch {
            val batch = mutableListOf<AuditEvent>()

            while (isActive) {
                try {
                    // Collect events up to batch size or timeout
                    val collected = collectBatch(batch)

                    // Flush batch if not empty
                    if (collected > 0) {
                        writer.writeBatch(batch)
                        batch.clear()
                    }
                } catch (e: CancellationException) {
                    // Graceful shutdown - flush remaining events
                    if (batch.isNotEmpty()) {
                        writer.writeBatch(batch)
                    }
                    throw e
                } catch (e: Exception) {
                    // Log error but continue processing
                    System.err.println("Error processing audit batch: ${e.message}")
                    e.printStackTrace()
                    // Clear bad batch to prevent infinite retry
                    batch.clear()
                }
            }
        }
    }

    override fun stop() {
        runBlocking {
            processorJob?.cancelAndJoin()
            scope.cancel()
        }
    }

    /**
     * Collect events into a batch up to batchSize or until timeout.
     *
     * @param batch The mutable list to collect events into
     * @return Number of events collected
     */
    private suspend fun collectBatch(batch: MutableList<AuditEvent>): Int {
        var collected = 0

        // Try to collect batch size with timeout
        withTimeoutOrNull(flushInterval) {
            repeat(batchSize) {
                val event = queue.receive()
                batch.add(event)
                collected++
            }
        }

        // If timeout occurred, collect any immediately available events
        while (collected < batchSize) {
            val event = queue.tryReceive().getOrNull() ?: break
            batch.add(event)
            collected++
        }

        return collected
    }
}

/**
 * Writer interface for persisting audit events to the database.
 */
internal interface AuditWriter {
    /**
     * Write a batch of events to persistent storage.
     *
     * @param events The events to persist
     */
    suspend fun writeBatch(events: List<AuditEvent>)
}

/**
 * Exposed-based writer for persisting audit events to the database.
 */
internal class ExposedAuditWriter : AuditWriter {

    override suspend fun writeBatch(events: List<AuditEvent>) {
        if (events.isEmpty()) return

        try {
            exposedTransaction {
                events.forEach { event ->
                    AuditLogDao.new {
                        this.eventType = event.eventType
                        this.timestamp = event.timestamp
                        this.actorId = event.actorId
                        this.actorType = event.actorType
                        this.targetId = event.targetId
                        this.targetType = event.targetType
                        this.result = event.result
                        this.metadata = event.metadata
                        this.realmId = event.realmId
                        this.sessionId = event.sessionId
                    }
                }
            }
        } catch (e: Exception) {
            // Log error but don't propagate - audit failures shouldn't crash the app
            System.err.println("Failed to write audit batch (${events.size} events): ${e.message}")
            e.printStackTrace()
        }
    }
}
