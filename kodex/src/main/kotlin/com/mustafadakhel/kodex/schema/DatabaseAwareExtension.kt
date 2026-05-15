package com.mustafadakhel.kodex.schema

public interface DatabaseAwareExtension {
    public fun initialize(db: KodexDatabase)
}
