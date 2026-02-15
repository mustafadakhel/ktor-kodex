package com.mustafadakhel.kodex.extension

public abstract class ExtensionConfig {
    public abstract fun build(context: ExtensionContext): RealmExtension
}
