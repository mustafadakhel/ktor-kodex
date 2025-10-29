package com.mustafadakhel.kodex.extension

/** Base class for extension configuration with DSL support. */
public abstract class ExtensionConfig {
    /** Builds the configured extension instance. */
    public abstract fun build(context: ExtensionContext): RealmExtension
}
