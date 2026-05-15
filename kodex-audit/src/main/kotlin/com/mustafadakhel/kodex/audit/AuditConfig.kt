package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.schema.AuditSchema
import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.schema.KodexDatabase
import io.ktor.utils.io.*

@KtorDsl
public class AuditConfig : ExtensionConfig() {

    /** Optional custom audit provider. If null, uses DatabaseAuditProvider (default). */
    public var provider: AuditProvider? = null

    override fun build(context: ExtensionContext): AuditExtension {
        val customProvider = provider
        val factory: (KodexDatabase, AuditSchema) -> AuditProvider = if (customProvider != null) {
            { _, _ -> customProvider }
        } else {
            { db, schema -> DatabaseAuditProvider(db, schema) }
        }
        return AuditExtension(factory)
    }
}
