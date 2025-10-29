package com.mustafadakhel.kodex.model.database

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import java.util.*

internal object UserProfiles : IdTable<UUID>() {
    override val id: Column<EntityID<UUID>> = reference("user_id", Users)
    val firstName = varchar("first_name", 255).nullable()
    val lastName = varchar("last_name", 255).nullable()
    val address = text("address").nullable()
    val profilePicture = text("profile_picture").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal class UserProfileDao(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UserProfileDao>(UserProfiles)

    var userId by UserProfiles.id
    var firstName by UserProfiles.firstName
    var lastName by UserProfiles.lastName
    var address by UserProfiles.address
    var profilePicture by UserProfiles.profilePicture
}
