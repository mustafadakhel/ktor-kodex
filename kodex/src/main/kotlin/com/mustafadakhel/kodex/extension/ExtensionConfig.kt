package com.mustafadakhel.kodex.extension

/**
 * Base class for extension configuration.
 * Each extension module provides a concrete implementation with its own DSL.
 *
 * Extensions receive an ExtensionContext during build(), providing access
 * to shared resources like realm information and time zone settings.
 */
public abstract class ExtensionConfig {
    /**
     * Builds and returns the configured extension instance.
     *
     * @param context Context providing access to realm configuration and shared resources
     * @return The configured extension
     */
    public abstract fun build(context: ExtensionContext): RealmExtension
}
