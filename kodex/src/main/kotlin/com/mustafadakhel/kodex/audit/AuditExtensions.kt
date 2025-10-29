package com.mustafadakhel.kodex.audit

import java.util.*

public suspend fun AuditService.audit(
    realmId: String,
    block: AuditEventBuilder.() -> Unit
) {
    val event = AuditEventBuilder(realmId).apply(block).build()
    log(event)
}

public suspend fun AuditService.auditLoginSuccess(
    realmId: String,
    userId: UUID,
    ipAddress: String,
    userAgent: String? = null,
    method: String = "email"
) = audit(realmId) {
    event(AuditEvents.LOGIN_SUCCESS)
    actor(userId, ipAddress = ipAddress, userAgent = userAgent)
    success()
    meta("method" to method)
}

public suspend fun AuditService.auditLoginFailed(
    realmId: String,
    identifier: String,
    reason: String,
    ipAddress: String,
    userAgent: String? = null
) = audit(realmId) {
    event(AuditEvents.LOGIN_FAILED)
    anonymousActor(ipAddress = ipAddress, userAgent = userAgent)
    subject(identifier)
    failure(reason)
}

public suspend fun AuditService.auditLogout(
    realmId: String,
    userId: UUID,
    ipAddress: String,
    userAgent: String? = null
) = audit(realmId) {
    event(AuditEvents.LOGOUT)
    actor(userId, ipAddress = ipAddress, userAgent = userAgent)
    success()
}

public suspend fun AuditService.auditTokenRefresh(
    realmId: String,
    userId: UUID,
    ipAddress: String
) = audit(realmId) {
    event(AuditEvents.TOKEN_REFRESH)
    actor(userId, ipAddress = ipAddress)
    success()
}

public suspend fun AuditService.auditUserCreated(
    realmId: String,
    userId: UUID,
    createdBy: UUID? = null,
    email: String? = null,
    phone: String? = null
) = audit(realmId) {
    event(AuditEvents.USER_CREATED)
    if (createdBy != null) {
        actor(createdBy, type = ActorType.ADMIN)
    } else {
        systemActor()
    }
    target(userId, "user")
    success()
    context {
        email?.let { "email" to it }
        phone?.let { "phone" to it }
    }
}

public suspend fun AuditService.auditUserUpdated(
    realmId: String,
    userId: UUID,
    updatedBy: UUID,
    changedFields: List<String>
) = audit(realmId) {
    event(AuditEvents.USER_UPDATED)
    actor(updatedBy)
    target(userId, "user")
    success()
    meta("changedFields" to changedFields)
}

public suspend fun AuditService.auditUserDeleted(
    realmId: String,
    userId: UUID,
    deletedBy: UUID,
    reason: String
) = audit(realmId) {
    event(AuditEvents.USER_DELETED)
    actor(deletedBy, type = ActorType.ADMIN)
    target(userId, "user")
    success()
    meta("reason" to reason)
}

public suspend fun AuditService.auditUserVerified(
    realmId: String,
    userId: UUID,
    verificationType: String
) = audit(realmId) {
    event(AuditEvents.USER_VERIFIED)
    systemActor()
    target(userId, "user")
    success()
    meta("verificationType" to verificationType)
}

public suspend fun AuditService.auditPasswordChanged(
    realmId: String,
    userId: UUID,
    changedBy: UUID,
    method: String = "self"
) = audit(realmId) {
    event(AuditEvents.PASSWORD_CHANGED)
    actor(changedBy)
    target(userId, "user")
    success()
    meta("method" to method)
}

public suspend fun AuditService.auditAccountLocked(
    realmId: String,
    identifier: String,
    attemptCount: Int,
    lockoutDuration: String
) = audit(realmId) {
    event(AuditEvents.ACCOUNT_LOCKED)
    systemActor()
    subject(identifier)
    success()
    context {
        "attemptCount" to attemptCount
        "lockoutDuration" to lockoutDuration
    }
}

public suspend fun AuditService.auditAccountUnlocked(
    realmId: String,
    identifier: String,
    unlockedBy: UUID? = null,
    reason: String = "Automatic expiration"
) = audit(realmId) {
    event(AuditEvents.ACCOUNT_UNLOCKED)
    if (unlockedBy != null) {
        actor(unlockedBy, type = ActorType.ADMIN)
    } else {
        systemActor()
    }
    subject(identifier)
    success()
    meta("reason" to reason)
}

public suspend fun AuditService.auditAccessGranted(
    realmId: String,
    userId: UUID,
    resource: String,
    action: String
) = audit(realmId) {
    event(AuditEvents.ACCESS_GRANTED)
    actor(userId)
    success()
    context {
        "resource" to resource
        "action" to action
    }
}

public suspend fun AuditService.auditAccessDenied(
    realmId: String,
    userId: UUID,
    resource: String,
    action: String,
    reason: String
) = audit(realmId) {
    event(AuditEvents.ACCESS_DENIED)
    actor(userId)
    failure(reason)
    context {
        "resource" to resource
        "action" to action
    }
}
