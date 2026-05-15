package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.schema.AuditSchema
import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.DatabaseAwareExtension
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase

public class AuditExtension internal constructor(
    private val providerFactory: (KodexDatabase, AuditSchema) -> AuditProvider
) : PersistentExtension, EventSubscriberProvider, DatabaseAwareExtension {

    private lateinit var provider: AuditProvider

    override fun createSchema(core: CoreSchema): ExtensionSchema = AuditSchema(core)

    override fun initialize(db: KodexDatabase) {
        val schema = db.schema<AuditSchema>()
        provider = providerFactory(db, schema)
    }

    override fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>> {
        return listOf(AuditEventSubscriber(provider))
    }
}
