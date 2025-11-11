package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import io.ktor.utils.io.*

/** Configuration for the audit logging extension */
@KtorDsl
public class AuditConfig : ExtensionConfig() {

    /** The audit provider to use for logging events (default: ConsoleAuditProvider) */
    public var provider: AuditProvider = ConsoleAuditProvider()

    override fun build(context: ExtensionContext): AuditExtension {
        return AuditExtension(provider)
    }
}
