package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.RealmExtension
import com.mustafadakhel.kodex.extension.Shutdownable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

public class AuditExtension internal constructor(
    private val provider: AuditProvider,
    private val retentionScheduler: AuditRetentionScheduler?
) : RealmExtension, EventSubscriberProvider, Shutdownable {

    private val retentionScope = retentionScheduler?.let {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    init {
        retentionScheduler?.let { scheduler ->
            retentionScope?.let { scope -> scheduler.start(scope) }
        }
    }

    override fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>> =
        listOf(AuditEventSubscriber(provider))

    override fun shutdown() {
        retentionScheduler?.stop()
        retentionScope?.cancel()
    }
}
