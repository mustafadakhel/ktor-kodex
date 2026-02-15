package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import io.ktor.utils.io.*

@KtorDsl
public class AuditConfig : ExtensionConfig() {

    /** The audit provider to use for logging events (default: DatabaseAuditProvider) */
    public var provider: AuditProvider = DatabaseAuditProvider()

    override fun build(context: ExtensionContext): AuditExtension {
        return AuditExtension(provider)
    }
}
