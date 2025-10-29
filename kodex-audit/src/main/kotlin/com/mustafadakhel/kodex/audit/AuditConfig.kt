package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.extension.ExtensionConfig
import io.ktor.utils.io.*

/**
 * Configuration for the audit logging extension.
 * Provides a type-safe DSL for configuring audit event logging.
 *
 * Example usage:
 * ```kotlin
 * realm("admin") {
 *     audit {
 *         provider = ConsoleAuditProvider()
 *         // Or use a custom provider:
 *         // provider = KafkaAuditProvider(kafkaConfig)
 *         // provider = DatabaseAuditProvider(dataSource)
 *     }
 * }
 * ```
 */
@KtorDsl
public class AuditConfig : ExtensionConfig() {

    /**
     * The audit provider to use for logging events.
     * Default: ConsoleAuditProvider()
     *
     * Built-in providers:
     * - ConsoleAuditProvider() - Logs to console (development)
     * - NoOpAuditProvider() - Disables audit logging
     *
     * Custom providers can be implemented by extending AuditProvider interface.
     */
    public var provider: AuditProvider = ConsoleAuditProvider()

    override fun build(context: com.mustafadakhel.kodex.extension.ExtensionContext): AuditExtension {
        return AuditExtension(provider)
    }
}
