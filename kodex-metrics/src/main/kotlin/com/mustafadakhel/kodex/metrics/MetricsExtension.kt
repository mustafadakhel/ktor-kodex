package com.mustafadakhel.kodex.metrics

import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.RealmExtension
import io.micrometer.core.instrument.MeterRegistry

public class MetricsExtension internal constructor(
    private val registry: MeterRegistry
) : RealmExtension, EventSubscriberProvider {

    override fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>> {
        val metrics = MicrometerMetrics(registry)
        return listOf(MetricsEventSubscriber(metrics))
    }
}
