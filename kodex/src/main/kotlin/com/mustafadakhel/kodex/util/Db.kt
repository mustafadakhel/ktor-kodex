package com.mustafadakhel.kodex.util

import java.util.concurrent.atomic.AtomicReference

internal object Db {
    private val engine = AtomicReference<DbEngine<*>?>(null)

    inline fun <reified Scope : Any> setEngine(newEngine: DbEngine<Scope>) {
        engine.set(newEngine)
    }

    internal inline fun <reified Scope, R> runInEngine(noinline block: Scope.() -> R): R {
        val eng = getEngine<Scope>()
        return eng.run(block)
    }

    internal inline fun <reified Scope> getEngine(): DbEngine<Scope> =
        getEngineOrNull<Scope>() ?: error("No engine registered for scope ${Scope::class.simpleName}")

    @Suppress("UNCHECKED_CAST")
    internal inline fun <reified Scope> getEngineOrNull(): DbEngine<Scope>? = engine.get() as? DbEngine<Scope>?

    internal fun clearEngine() {
        engine.getAndSet(null)?.clear()
    }
}
