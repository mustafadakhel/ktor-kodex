package com.mustafadakhel.kodex.audit

import io.ktor.utils.io.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.*

@DslMarker
public annotation class AuditDsl

@AuditDsl
public class AuditEventBuilder internal constructor(
    private val realmId: String,
    private var currentTimestamp: Instant = Clock.System.now()
) {
    private var eventType: String? = null
    private var actorId: UUID? = null
    private var actorType: ActorType = ActorType.USER
    private var targetId: UUID? = null
    private var targetType: String? = null
    private var result: EventResult = EventResult.SUCCESS
    private var sessionId: UUID? = null
    private val metadata = mutableMapOf<String, Any>()

    public fun event(type: String) {
        eventType = type
    }

    public fun actor(
        id: UUID,
        type: ActorType = ActorType.USER,
        ipAddress: String? = null,
        userAgent: String? = null
    ) {
        actorId = id
        actorType = type
        ipAddress?.let { metadata["ipAddress"] = it }
        userAgent?.let { metadata["userAgent"] = it }
    }

    public fun systemActor(ipAddress: String? = null) {
        actorType = ActorType.SYSTEM
        actorId = null
        ipAddress?.let { metadata["ipAddress"] = it }
    }

    public fun anonymousActor(ipAddress: String? = null, userAgent: String? = null) {
        actorType = ActorType.ANONYMOUS
        actorId = null
        ipAddress?.let { metadata["ipAddress"] = it }
        userAgent?.let { metadata["userAgent"] = it }
    }

    public fun subject(identifier: String) {
        metadata["subject"] = identifier
    }

    public fun target(id: UUID, type: String? = null) {
        targetId = id
        targetType = type
    }

    public fun success() {
        result = EventResult.SUCCESS
    }

    public fun failure(reason: String? = null) {
        result = EventResult.FAILURE
        reason?.let { metadata["reason"] = it }
    }

    public fun partialSuccess(reason: String? = null) {
        result = EventResult.PARTIAL_SUCCESS
        reason?.let { metadata["reason"] = it }
    }

    public fun result(eventResult: EventResult, reason: String? = null) {
        result = eventResult
        reason?.let { metadata["reason"] = it }
    }

    public fun session(id: UUID) {
        sessionId = id
    }

    public fun timestamp(timestamp: Instant) {
        currentTimestamp = timestamp
    }

    public fun context(block: MutableMap<String, Any>.() -> Unit) {
        metadata.apply(block)
    }

    public fun meta(key: String, value: Any) {
        metadata[key] = value
    }

    public fun meta(vararg entries: Pair<String, Any>) {
        metadata.putAll(entries)
    }

    internal fun build(): AuditEvent {
        require(eventType != null) { "Event type is required" }

        return AuditEvent(
            eventType = eventType!!,
            timestamp = currentTimestamp,
            actorId = actorId,
            actorType = actorType,
            targetId = targetId,
            targetType = targetType,
            result = result,
            metadata = metadata.toMap(),
            realmId = realmId,
            sessionId = sessionId
        )
    }
}
