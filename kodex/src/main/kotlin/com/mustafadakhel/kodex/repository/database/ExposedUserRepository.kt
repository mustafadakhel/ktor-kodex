package com.mustafadakhel.kodex.repository.database

import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.FullUserEntity
import com.mustafadakhel.kodex.model.database.RoleEntity
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.model.database.UserProfileEntity
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.update.FieldUpdate
import org.jetbrains.exposed.exceptions.ExposedSQLException
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.statements.UpdateStatement
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

internal fun databaseUserRepository(db: KodexDatabase, realmId: String): UserRepository =
    ExposedUserRepository(db, realmId)

internal class ExposedUserRepository(
    private val db: KodexDatabase,
    private val realmId: String,
) : UserRepository {

    private val users get() = db.core.users
    private val roles get() = db.core.roles
    private val userRoles get() = db.core.userRoles
    private val profiles get() = db.core.userProfiles
    private val customAttrs get() = db.core.userCustomAttributes

    override fun getAll(): List<UserEntity> = db.transaction {
        users.selectAll()
            .where { users.realmId eq realmId }
            .map { it.toUserEntity() }
    }

    override fun getAllFull(): List<FullUserEntity> = db.transaction {
        val userRows = users.selectAll()
            .where { users.realmId eq realmId }
            .toList()

        if (userRows.isEmpty()) return@transaction emptyList()

        val userIds = userRows.map { it[users.id] }

        val userRolesMap = loadRolesByUserIds(userIds)
        val userProfilesMap = loadProfilesByUserIds(userIds)
        val userAttributesMap = loadCustomAttributesByUserIds(userIds)

        userRows.map { row ->
            val userId = row[users.id].value
            row.toFullUserEntity(
                roles = userRolesMap[userId] ?: emptyList(),
                profile = userProfilesMap[userId],
                customAttributes = userAttributesMap[userId] ?: emptyMap(),
            )
        }
    }

    override fun findById(userId: UUID): UserEntity? = db.transaction {
        users.selectAll()
            .where { (users.id eq userId) and (users.realmId eq realmId) }
            .firstOrNull()
            ?.toUserEntity()
    }

    override fun findByPhone(phone: String): UserEntity? = db.transaction {
        users.selectAll()
            .where { (users.phoneNumber eq phone) and (users.realmId eq realmId) }
            .firstOrNull()
            ?.toUserEntity()
    }

    override fun findByEmail(email: String): UserEntity? = db.transaction {
        users.selectAll()
            .where { (users.email eq email) and (users.realmId eq realmId) }
            .firstOrNull()
            ?.toUserEntity()
    }

    override fun findFullById(userId: UUID): FullUserEntity? = db.transaction {
        val row = users.selectAll()
            .where { (users.id eq userId) and (users.realmId eq realmId) }
            .firstOrNull()
            ?: return@transaction null

        val entityId = row[users.id]
        val rolesForUser = loadRolesForUser(entityId)
        val profile = loadProfileForUser(entityId)
        val attributes = loadCustomAttributesForUser(entityId)

        row.toFullUserEntity(
            roles = rolesForUser,
            profile = profile,
            customAttributes = attributes,
        )
    }

    override fun create(
        email: String?,
        phone: String?,
        hashedPassword: String,
        roleNames: List<String>,
        customAttributes: Map<String, String>?,
        profile: UserProfile?,
        currentTime: LocalDateTime,
    ): UserRepository.CreateUserResult {
        return try {
            db.transaction {
                val invalidRole = findFirstInvalidRole(roleNames)
                if (invalidRole != null) return@transaction UserRepository.CreateUserResult.InvalidRole(invalidRole)

                val roleIdMap = lookupRoleIds(roleNames)

                val realmValue = this@ExposedUserRepository.realmId
                val newUserId = users.insertAndGetId {
                    it[users.passwordHash] = hashedPassword
                    it[users.createdAt] = currentTime
                    it[users.updatedAt] = currentTime
                    it[users.email] = email
                    it[users.phoneNumber] = phone
                    it[users.realmId] = realmValue
                }

                for (roleName in roleNames) {
                    userRoles.insert {
                        it[userRoles.userId] = newUserId
                        it[userRoles.roleId] = roleIdMap.getValue(roleName)
                    }
                }

                if (profile != null) insertProfile(newUserId.value, profile)
                if (customAttributes != null) insertCustomAttributes(newUserId.value, customAttributes)

                val createdRow = users.selectAll()
                    .where { users.id eq newUserId }
                    .single()

                UserRepository.CreateUserResult.Success(createdRow.toUserEntity())
            }
        } catch (e: ExposedSQLException) {
            mapCreateConstraintViolation(e)
        }
    }

    private fun mapCreateConstraintViolation(e: ExposedSQLException): UserRepository.CreateUserResult =
        when (detectDuplicateField(e)) {
            DuplicateField.EMAIL -> UserRepository.CreateUserResult.EmailAlreadyExists
            DuplicateField.PHONE -> UserRepository.CreateUserResult.PhoneAlreadyExists
            null -> throw e
        }

    override fun getHashedPassword(userId: UUID): String? = db.transaction {
        users.selectAll()
            .where { (users.id eq userId) and (users.realmId eq realmId) }
            .firstOrNull()
            ?.get(users.passwordHash)
    }

    override fun seedRoles(roles: List<Role>): Unit = db.transaction {
        val rolesTable = this@ExposedUserRepository.roles
        val realmValue = this@ExposedUserRepository.realmId
        for (role in roles) {
            val existing = rolesTable.selectAll()
                .where { (rolesTable.name eq role.name) and (rolesTable.realmId eq realmValue) }
                .singleOrNull()
            if (existing != null) {
                rolesTable.update({
                    (rolesTable.name eq role.name) and (rolesTable.realmId eq realmValue)
                }) {
                    it[rolesTable.description] = role.description
                }
            } else {
                rolesTable.insert {
                    it[rolesTable.name] = role.name
                    it[rolesTable.realmId] = realmValue
                    it[rolesTable.description] = role.description
                }
            }
        }
    }

    override fun updateById(
        userId: UUID,
        email: FieldUpdate<String>,
        phone: FieldUpdate<String>,
        status: FieldUpdate<UserStatus>,
        currentTime: LocalDateTime,
    ): UserRepository.UpdateUserResult {
        return try {
            db.transaction {
                if (!userExistsInRealm(userId)) return@transaction UserRepository.UpdateUserResult.NotFound

                users.update({ users.id eq userId }) {
                    applyFieldUpdates(it, email, phone, status, currentTime)
                }

                UserRepository.UpdateUserResult.Success
            }
        } catch (e: ExposedSQLException) {
            mapUpdateConstraintViolation(e)
        }
    }

    override fun updateRolesForUser(
        userId: UUID,
        roleNames: List<String>,
    ): UserRepository.UpdateRolesResult = db.transaction {
        replaceRolesForUser(userId, roleNames)
    }

    override fun findRoles(userId: UUID): List<RoleEntity> = db.transaction {
        if (!userExistsInRealm(userId)) return@transaction emptyList()

        userRoles.innerJoin(roles)
            .selectAll()
            .where {
                (userRoles.userId eq userId) and
                (roles.realmId eq realmId)
            }
            .map { it.toRoleEntity() }
    }

    override fun getAllRoles(): List<RoleEntity> = db.transaction {
        roles.selectAll()
            .where { roles.realmId eq realmId }
            .map { it.toRoleEntity() }
    }

    override fun findProfileByUserId(userId: UUID): UserProfileEntity? = db.transaction {
        if (!userExistsInRealm(userId)) return@transaction null

        profiles.selectAll()
            .where { profiles.userId eq userId }
            .firstOrNull()
            ?.toProfileEntity()
    }

    override fun updateProfileByUserId(userId: UUID, profile: UserProfile): UserRepository.UpdateProfileResult =
        db.transaction {
            val userRow = users.selectAll()
                .where { (users.id eq userId) and (users.realmId eq realmId) }
                .firstOrNull()
                ?: return@transaction UserRepository.UpdateProfileResult.NotFound

            upsertProfile(userId, profile)

            UserRepository.UpdateProfileResult.Success(userRow.toUserEntity())
        }

    override fun findCustomAttributesByUserId(userId: UUID): Map<String, String> = db.transaction {
        if (!userExistsInRealm(userId)) return@transaction emptyMap()

        customAttrs.selectAll()
            .where { customAttrs.userId eq userId }
            .associate { it[customAttrs.key] to it[customAttrs.value] }
    }

    override fun replaceAllCustomAttributesByUserId(
        userId: UUID,
        customAttributes: Map<String, String>,
    ): UserRepository.UpdateUserResult = db.transaction {
        if (!userExistsInRealm(userId)) return@transaction UserRepository.UpdateUserResult.NotFound

        validateCustomAttributes(customAttributes)
        customAttrs.deleteWhere { customAttrs.userId eq userId }
        insertCustomAttributes(userId, customAttributes)

        UserRepository.UpdateUserResult.Success
    }

    override fun updateCustomAttributesByUserId(
        userId: UUID,
        customAttributes: Map<String, String>,
    ): UserRepository.UpdateUserResult = db.transaction {
        if (!userExistsInRealm(userId)) return@transaction UserRepository.UpdateUserResult.NotFound

        upsertCustomAttributes(userId, customAttributes)

        UserRepository.UpdateUserResult.Success
    }

    override fun updateLastLogin(userId: UUID, loginTime: LocalDateTime): Boolean = db.transaction {
        val updated = users.update({
            (users.id eq userId) and (users.realmId eq realmId)
        }) {
            it[users.lastLoginAt] = loginTime
        }
        updated > 0
    }

    override fun updatePassword(userId: UUID, hashedPassword: String): Boolean = db.transaction {
        val updated = users.update({
            (users.id eq userId) and (users.realmId eq realmId)
        }) {
            it[users.passwordHash] = hashedPassword
        }
        updated > 0
    }

    override fun deleteUser(userId: UUID): UserRepository.DeleteResult = db.transaction {
        val deleted = users.deleteWhere {
            (users.id eq userId) and (users.realmId eq realmId)
        }
        if (deleted > 0) UserRepository.DeleteResult.Success
        else UserRepository.DeleteResult.NotFound
    }

    override fun updateBatch(
        userId: UUID,
        email: FieldUpdate<String>,
        phone: FieldUpdate<String>,
        status: FieldUpdate<UserStatus>,
        profile: FieldUpdate<UserProfile>,
        customAttributes: FieldUpdate<Map<String, String>>,
        currentTime: LocalDateTime,
    ): UserRepository.UpdateUserResult {
        return try {
            db.transaction {
                if (!userExistsInRealm(userId)) return@transaction UserRepository.UpdateUserResult.NotFound

                users.update({ users.id eq userId }) {
                    applyFieldUpdates(it, email, phone, status, currentTime)
                }

                when (profile) {
                    is FieldUpdate.NoChange -> {}
                    is FieldUpdate.SetValue -> {
                        upsertProfile(userId, profile.value)
                    }
                    is FieldUpdate.ClearValue -> {
                        profiles.deleteWhere { profiles.userId eq userId }
                    }
                }

                when (customAttributes) {
                    is FieldUpdate.NoChange -> {}
                    is FieldUpdate.SetValue -> upsertCustomAttributes(userId, customAttributes.value)
                    is FieldUpdate.ClearValue -> {
                        customAttrs.deleteWhere { customAttrs.userId eq userId }
                    }
                }

                UserRepository.UpdateUserResult.Success
            }
        } catch (e: ExposedSQLException) {
            mapUpdateConstraintViolation(e)
        }
    }

    private fun loadRolesByUserIds(
        userIds: List<EntityID<UUID>>,
    ): Map<UUID, List<RoleEntity>> {
        val result = mutableMapOf<UUID, MutableList<RoleEntity>>()
        userRoles.innerJoin(roles)
            .selectAll()
            .where {
                (userRoles.userId inList userIds) and
                (roles.realmId eq realmId)
            }
            .forEach { row ->
                val uid = row[userRoles.userId].value
                result.getOrPut(uid) { mutableListOf() }.add(row.toRoleEntity())
            }
        return result
    }

    private fun loadProfilesByUserIds(
        userIds: List<EntityID<UUID>>,
    ): Map<UUID, UserProfileEntity> =
        profiles.selectAll()
            .where { profiles.userId inList userIds }
            .associate { row ->
                row[profiles.userId].value to row.toProfileEntity()
            }

    private fun loadCustomAttributesByUserIds(
        userIds: List<EntityID<UUID>>,
    ): Map<UUID, Map<String, String>> =
        customAttrs.selectAll()
            .where { customAttrs.userId inList userIds }
            .groupBy { it[customAttrs.userId].value }
            .mapValues { (_, rows) ->
                rows.associate { it[customAttrs.key] to it[customAttrs.value] }
            }

    private fun loadRolesForUser(userEntityId: EntityID<UUID>): List<RoleEntity> =
        userRoles.innerJoin(roles)
            .selectAll()
            .where {
                (userRoles.userId eq userEntityId) and
                (roles.realmId eq realmId)
            }
            .map { it.toRoleEntity() }

    private fun loadProfileForUser(userEntityId: EntityID<UUID>): UserProfileEntity? =
        profiles.selectAll()
            .where { profiles.userId eq userEntityId }
            .firstOrNull()
            ?.toProfileEntity()

    private fun loadCustomAttributesForUser(userEntityId: EntityID<UUID>): Map<String, String> =
        customAttrs.selectAll()
            .where { customAttrs.userId eq userEntityId }
            .associate { it[customAttrs.key] to it[customAttrs.value] }

    private fun insertProfile(userId: UUID, profile: UserProfile) {
        profiles.insert {
            it[profiles.userId] = EntityID(userId, users)
            setProfileFields(it, profile)
        }
    }

    private fun upsertProfile(userId: UUID, profile: UserProfile) {
        val updated = profiles.update({ profiles.userId eq userId }) {
            setProfileFields(it, profile)
        }
        if (updated == 0) insertProfile(userId, profile)
    }

    private fun setProfileFields(stmt: UpdateBuilder<*>, profile: UserProfile) {
        stmt[profiles.firstName] = profile.firstName
        stmt[profiles.lastName] = profile.lastName
        stmt[profiles.address] = profile.address
        stmt[profiles.profilePicture] = profile.profilePicture
    }

    private fun insertCustomAttributes(userId: UUID, attributes: Map<String, String>) {
        validateCustomAttributes(attributes)
        val entityId = EntityID(userId, users)
        for ((attrKey, attrValue) in attributes) {
            customAttrs.insert {
                it[customAttrs.userId] = entityId
                it[customAttrs.key] = attrKey
                it[customAttrs.value] = attrValue
            }
        }
    }

    private fun upsertCustomAttributes(userId: UUID, attributes: Map<String, String>) {
        val existing = customAttrs.selectAll()
            .where { customAttrs.userId eq userId }
            .associate { it[customAttrs.key] to it[customAttrs.id] }

        val newKeysCount = attributes.keys.count { it !in existing }
        val totalAfterUpdate = existing.size + newKeysCount
        require(totalAfterUpdate <= MAX_ATTRIBUTES_PER_USER) {
            "Too many custom attributes after update (max: $MAX_ATTRIBUTES_PER_USER, would be: $totalAfterUpdate)"
        }

        val entityId = EntityID(userId, users)
        for ((attrKey, attrValue) in attributes) {
            validateKey(attrKey)
            validateValue(attrKey, attrValue)
            val existingId = existing[attrKey]
            if (existingId != null) {
                customAttrs.update({ customAttrs.id eq existingId }) {
                    it[customAttrs.value] = attrValue
                }
            } else {
                customAttrs.insert {
                    it[customAttrs.userId] = entityId
                    it[customAttrs.key] = attrKey
                    it[customAttrs.value] = attrValue
                }
            }
        }
    }

    private fun replaceRolesForUser(
        userId: UUID,
        roleNames: List<String>,
    ): UserRepository.UpdateRolesResult {
        if (!userExistsInRealm(userId)) return UserRepository.UpdateRolesResult.InvalidRole("")

        val invalidRole = findFirstInvalidRole(roleNames)
        if (invalidRole != null) return UserRepository.UpdateRolesResult.InvalidRole(invalidRole)

        val roleIdMap = lookupRoleIds(roleNames)

        userRoles.deleteWhere { userRoles.userId eq userId }

        val entityId = EntityID(userId, users)
        for (roleName in roleNames) {
            userRoles.insert {
                it[userRoles.userId] = entityId
                it[userRoles.roleId] = roleIdMap.getValue(roleName)
            }
        }
        return UserRepository.UpdateRolesResult.Success
    }

    private fun userExistsInRealm(userId: UUID): Boolean =
        users.selectAll()
            .where { (users.id eq userId) and (users.realmId eq realmId) }
            .any()

    private fun findFirstInvalidRole(roleNames: List<String>): String? {
        if (roleNames.isEmpty()) return null
        val existingRoles = roles.selectAll()
            .where { (roles.name inList roleNames) and (roles.realmId eq realmId) }
            .map { it[roles.name] }
            .toSet()
        return roleNames.firstOrNull { it !in existingRoles }
    }

    private fun lookupRoleIds(roleNames: List<String>): Map<String, EntityID<UUID>> =
        roles.selectAll()
            .where { (roles.name inList roleNames) and (roles.realmId eq realmId) }
            .associate { it[roles.name] to it[roles.id] }

    private fun mapUpdateConstraintViolation(e: ExposedSQLException): UserRepository.UpdateUserResult =
        when (detectDuplicateField(e)) {
            DuplicateField.EMAIL -> UserRepository.UpdateUserResult.EmailAlreadyExists
            DuplicateField.PHONE -> UserRepository.UpdateUserResult.PhoneAlreadyExists
            null -> throw e
        }

    private fun detectDuplicateField(e: ExposedSQLException): DuplicateField? {
        if (e.sqlState?.startsWith("23") != true) return null
        val msg = e.message ?: return null
        return when {
            msg.contains(users.emailRealmIndex, ignoreCase = true) -> DuplicateField.EMAIL
            msg.contains(users.phoneRealmIndex, ignoreCase = true) -> DuplicateField.PHONE
            else -> null
        }
    }

    private enum class DuplicateField { EMAIL, PHONE }

    private fun applyFieldUpdates(
        stmt: UpdateStatement,
        email: FieldUpdate<String>,
        phone: FieldUpdate<String>,
        status: FieldUpdate<UserStatus>,
        currentTime: LocalDateTime,
    ) {
        when (email) {
            is FieldUpdate.NoChange -> {}
            is FieldUpdate.SetValue -> stmt[users.email] = email.value
            is FieldUpdate.ClearValue -> stmt[users.email] = null
        }
        when (phone) {
            is FieldUpdate.NoChange -> {}
            is FieldUpdate.SetValue -> stmt[users.phoneNumber] = phone.value
            is FieldUpdate.ClearValue -> stmt[users.phoneNumber] = null
        }
        when (status) {
            is FieldUpdate.NoChange -> {}
            is FieldUpdate.SetValue -> stmt[users.status] = status.value
            is FieldUpdate.ClearValue -> {}
        }
        stmt[users.updatedAt] = currentTime
    }

    private fun validateCustomAttributes(attributes: Map<String, String>) {
        require(attributes.size <= MAX_ATTRIBUTES_PER_USER) {
            "Too many custom attributes (max: $MAX_ATTRIBUTES_PER_USER, attempted: ${attributes.size})"
        }
        for ((key, value) in attributes) {
            validateKey(key)
            validateValue(key, value)
        }
    }

    private fun validateKey(key: String) {
        require(key.isNotBlank()) { "Custom attribute key cannot be blank" }
        require(key.length <= MAX_KEY_LENGTH) {
            "Custom attribute key too long (max: $MAX_KEY_LENGTH, actual: ${key.length})"
        }
        require(key.matches(VALID_KEY_PATTERN)) {
            "Custom attribute key contains invalid characters (allowed: a-zA-Z0-9_-): $key"
        }
        require(key.lowercase() !in BLOCKED_KEYS) {
            "Custom attribute key is blocked for security reasons: $key"
        }
    }

    private fun validateValue(key: String, value: String) {
        require(value.length <= MAX_VALUE_LENGTH) {
            "Custom attribute value too long (max: $MAX_VALUE_LENGTH, key: $key, actual: ${value.length})"
        }
    }

    private fun ResultRow.toUserEntity() = UserEntity(
        id = this[users.id].value,
        createdAt = this[users.createdAt],
        updatedAt = this[users.updatedAt],
        phoneNumber = this[users.phoneNumber],
        email = this[users.email],
        lastLoggedIn = this[users.lastLoginAt],
        status = this[users.status],
    )

    private fun ResultRow.toFullUserEntity(
        roles: List<RoleEntity>,
        profile: UserProfileEntity?,
        customAttributes: Map<String, String>,
    ) = FullUserEntity(
        id = this[users.id].value,
        createdAt = this[users.createdAt],
        updatedAt = this[users.updatedAt],
        phoneNumber = this[users.phoneNumber],
        email = this[users.email],
        lastLoggedIn = this[users.lastLoginAt],
        status = this[users.status],
        roles = roles,
        profile = profile,
        customAttributes = customAttributes,
    )

    private fun ResultRow.toProfileEntity() = UserProfileEntity(
        userId = this[profiles.userId].value,
        firstName = this[profiles.firstName],
        lastName = this[profiles.lastName],
        address = this[profiles.address],
        profilePicture = this[profiles.profilePicture],
    )

    private fun ResultRow.toRoleEntity() = RoleEntity(
        name = this[roles.name],
        description = this[roles.description],
    )

    private companion object {
        const val MAX_ATTRIBUTES_PER_USER = 100
        const val MAX_KEY_LENGTH = 100
        const val MAX_VALUE_LENGTH = 4096
        val VALID_KEY_PATTERN = Regex("^[a-zA-Z0-9_-]+$")
        val BLOCKED_KEYS = setOf(
            "__proto__",
            "constructor",
            "prototype",
            "__definegetter__",
            "__definesetter__",
            "__lookupgetter__",
            "__lookupsetter__",
        )
    }
}
