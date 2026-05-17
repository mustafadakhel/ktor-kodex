package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase

public abstract class ExtensionConfig {

    /** Returns the schema this extension needs, or null for non-persistent extensions. */
    public open fun schema(tablePrefix: String): ExtensionSchema? = null

    public abstract fun build(context: ExtensionContext, db: KodexDatabase): RealmExtension
}
