package com.mustafadakhel.kodex.metrics

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.schema.KodexDatabase
import io.ktor.utils.io.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

@KtorDsl
public class MetricsConfig : ExtensionConfig() {

    /**
     * Micrometer registry for publishing metrics.
     * Default: SimpleMeterRegistry (in-memory, for development)
     *
     * Production: PrometheusMeterRegistry, DatadogMeterRegistry, CloudWatchMeterRegistry, etc.
     */
    public var registry: MeterRegistry = SimpleMeterRegistry()

    override fun build(context: ExtensionContext, db: KodexDatabase): MetricsExtension {
        return MetricsExtension(registry)
    }
}
