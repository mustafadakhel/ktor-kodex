package com.mustafadakhel.kodex.model.database

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import java.util.UUID

internal object UserRoles : Table() {
    public val userId: Column<EntityID<UUID>> = reference("user_id", Users)
    public val roleId: Column<EntityID<String>> = reference("role_id", Roles)

    override val primaryKey: PrimaryKey = PrimaryKey(userId, roleId)
}