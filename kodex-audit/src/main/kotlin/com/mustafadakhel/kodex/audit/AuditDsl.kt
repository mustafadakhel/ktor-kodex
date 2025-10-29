package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.routes.auth.RealmConfigScope

/**
 * Configure audit event logging.
 *
 * Example:
 * ```kotlin
 * realm("admin") {
 *     audit {
 *         provider = ConsoleAuditProvider()
 *         // Or use a custom provider:
 *         // provider = KafkaAuditProvider(kafkaConfig)
 *     }
 * }
 * ```
 */
public fun RealmConfigScope.audit(block: AuditConfig.() -> Unit) {
    extension(AuditConfig(), block)
}
