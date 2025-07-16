package com.mustafadakhel.kodex.util

internal object Db {
    private var engine: DbEngine<*>? = null

    inline fun <reified Scope : Any> setEngine(engine: DbEngine<Scope>) {
        this.engine = engine
    }

    internal inline fun <reified Scope, R> runInEngine(noinline block: Scope.() -> R): R {
        val engine = getEngine<Scope>()
        return engine.run(block)
    }

    internal inline fun <reified Scope> getEngine(): DbEngine<Scope> =
        getEngineOrNull<Scope>() ?: error("No engine registered for scope ${Scope::class.simpleName}")

    @Suppress("UNCHECKED_CAST")
    internal inline fun <reified Scope> getEngineOrNull(): DbEngine<Scope>? = engine as? DbEngine<Scope>?

    internal fun clearEngine() {
        engine?.clear()
        engine = null
    }
}