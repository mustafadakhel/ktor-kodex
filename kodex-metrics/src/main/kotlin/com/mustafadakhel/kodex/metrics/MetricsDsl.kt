package com.mustafadakhel.kodex.metrics

import com.mustafadakhel.kodex.routes.auth.RealmConfigScope

public fun RealmConfigScope.metrics(block: MetricsConfig.() -> Unit) {
    extension(MetricsConfig(), block)
}
