package com.mustafadakhel.kodex.model.database

import org.jetbrains.exposed.sql.Table

internal object UserRoles : Table() {
    val userId = reference("user_id", Users)
    val roleId = reference("role_id", Roles)

    override val primaryKey = PrimaryKey(userId, roleId)
}