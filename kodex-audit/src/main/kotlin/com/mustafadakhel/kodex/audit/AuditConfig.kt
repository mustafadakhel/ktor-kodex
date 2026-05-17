package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.schema.AuditSchema
import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import io.ktor.utils.io.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@KtorDsl
public class AuditConfig : ExtensionConfig() {

    /** Optional custom audit provider. If null, uses DatabaseAuditProvider (default). */
    public var provider: AuditProvider? = null

    /** How long to retain audit logs before automatic cleanup. Default: 90 days. */
    public var retentionPeriod: Duration = 90.days

    /** How often the retention scheduler checks for expired logs. Default: 6 hours. */
    public var retentionCheckInterval: Duration = 6.hours

    /** Whether automatic audit log retention is enabled. Default: true. */
    public var retentionEnabled: Boolean = true

    override fun schema(tablePrefix: String): ExtensionSchema = AuditSchema(tablePrefix)

    override fun build(context: ExtensionContext, db: KodexDatabase): AuditExtension {
        val schema = db.schema<AuditSchema>()
        val auditProvider = provider ?: DatabaseAuditProvider(db, schema)

        val retentionConfig = if (retentionEnabled) {
            val retentionService = auditRetentionService(
                db = db,
                schema = schema,
                config = AuditRetentionConfig(retentionPeriod = retentionPeriod),
                realmId = context.realm.name
            )
            val scheduler = AuditRetentionScheduler(retentionService, retentionCheckInterval)
            scheduler
        } else null

        return AuditExtension(auditProvider, retentionConfig)
    }
}
