package com.mustafadakhel.kodex.model.database

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable

internal object Roles : IdTable<String>("roles") {
    override val id = varchar("name", 50).entityId()
    val description = text("description").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal class RoleDao(name: EntityID<String>) : Entity<String>(name) {
    companion object : EntityClass<String, RoleDao>(Roles)

    var name by Roles.id
    var description by Roles.description
}