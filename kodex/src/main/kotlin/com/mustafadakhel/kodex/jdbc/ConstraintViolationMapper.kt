package com.mustafadakhel.kodex.jdbc

import java.sql.SQLException

public object ConstraintViolationMapper {
    public fun detectDuplicateIndex(e: SQLException, vararg indexNames: String): String? {
        if (e.sqlState?.startsWith("23") != true) return null
        val msg = e.message ?: return null
        return indexNames.firstOrNull { msg.contains(it, ignoreCase = true) }
    }
}
