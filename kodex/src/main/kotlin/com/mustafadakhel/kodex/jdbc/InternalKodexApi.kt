package com.mustafadakhel.kodex.jdbc

@RequiresOptIn(
    message = "This is internal Kodex JDBC API. Do not use in application code.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
public annotation class InternalKodexApi

@DslMarker
public annotation class KodexJdbcDsl
