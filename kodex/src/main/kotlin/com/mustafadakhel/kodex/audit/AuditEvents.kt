package com.mustafadakhel.kodex.audit

/**
 * Standard audit event type constants.
 * Extensions can use these for consistency or define their own event types.
 */
public object AuditEvents {
    // Authentication
    public const val LOGIN_SUCCESS: String = "auth.login.success"
    public const val LOGIN_FAILED: String = "auth.login.failed"
    public const val LOGOUT: String = "auth.logout"
    public const val TOKEN_REFRESH: String = "auth.token.refresh"
    public const val TOKEN_REFRESH_FAILED: String = "auth.token.refresh.failed"
    public const val TOKEN_REVOKED: String = "auth.token.revoked"
    public const val ACCOUNT_LOCKED: String = "auth.account.locked"
    public const val ACCOUNT_UNLOCKED: String = "auth.account.unlocked"

    // User Management
    public const val USER_CREATED: String = "user.created"
    public const val USER_UPDATED: String = "user.updated"
    public const val USER_DELETED: String = "user.deleted"
    public const val USER_VERIFIED: String = "user.verified"
    public const val USER_VERIFICATION_CHANGED: String = "user.verification.changed"
    public const val PASSWORD_CHANGED: String = "user.password.changed"
    public const val PASSWORD_CHANGE_FAILED: String = "user.password.change.failed"
    public const val PROFILE_UPDATED: String = "user.profile.updated"

    // Authorization
    public const val ACCESS_GRANTED: String = "authz.access.granted"
    public const val ACCESS_DENIED: String = "authz.access.denied"
    public const val ROLE_ASSIGNED: String = "authz.role.assigned"
    public const val ROLE_REVOKED: String = "authz.role.revoked"
    public const val PERMISSION_GRANTED: String = "authz.permission.granted"
    public const val PERMISSION_REVOKED: String = "authz.permission.revoked"

    // Administrative
    public const val ADMIN_ACTION: String = "admin.action"
    public const val CONFIG_CHANGED: String = "admin.config.changed"
    public const val BULK_OPERATION: String = "admin.bulk.operation"
    public const val REALM_CREATED: String = "admin.realm.created"
    public const val REALM_CONFIG_CHANGED: String = "admin.realm.config.changed"

    // Data (GDPR)
    public const val DATA_EXPORTED: String = "data.export"
    public const val DATA_ANONYMIZED: String = "data.anonymized"
    public const val DATA_DELETED: String = "data.deleted"
    public const val CONSENT_RECORDED: String = "data.consent.recorded"
    public const val CONSENT_WITHDRAWN: String = "data.consent.withdrawn"

    // Security
    public const val SUSPICIOUS_ACTIVITY: String = "security.suspicious.activity"
    public const val SECURITY_VIOLATION: String = "security.violation"
    public const val RATE_LIMIT_EXCEEDED: String = "security.rate.limit.exceeded"
}
