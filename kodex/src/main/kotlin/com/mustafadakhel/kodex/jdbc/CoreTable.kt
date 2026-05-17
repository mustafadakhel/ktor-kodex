package com.mustafadakhel.kodex.jdbc

public sealed interface CoreTable {
    public val tableSuffix: String
    public val idColumnName: String

    public data object Users : CoreTable {
        override val tableSuffix: String = "users"
        override val idColumnName: String = "id"
    }

    public data object Roles : CoreTable {
        override val tableSuffix: String = "roles"
        override val idColumnName: String = "id"
    }
}
