package com.mustafadakhel.kodex.event

import java.util.UUID

public enum class FailureReason {
    INVALID_PASSWORD,
    USER_NOT_FOUND,
    ACCOUNT_LOCKED,
    ACCOUNT_UNVERIFIED,
    IP_BLOCKED,
    RATE_LIMITED,
    INSUFFICIENT_PERMISSIONS,
    INVALID_TOKEN,
    TOKEN_EXPIRED,
    TOKEN_REVOKED,
    INVALID_CREDENTIALS,
    UNKNOWN
}

public enum class ChangeType {
    ADDED,
    MODIFIED,
    REMOVED
}

public data class FieldChange(
    val fieldName: String,
    val oldValue: Any?,
    val newValue: Any?,
    val changeType: ChangeType
)

public data class RequestContext(
    val requestId: UUID?,
    val sessionId: UUID?,
    val sourceIp: String?,
    val userAgent: String?,
    val correlationId: UUID? = requestId,
    val causedByEventId: UUID? = null
)
