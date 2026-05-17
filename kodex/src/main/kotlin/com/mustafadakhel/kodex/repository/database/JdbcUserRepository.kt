@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.repository.database

import com.mustafadakhel.kodex.jdbc.ConnectionScope
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.ConstraintViolationMapper
import com.mustafadakhel.kodex.jdbc.InsertBuilder
import com.mustafadakhel.kodex.jdbc.Row
import com.mustafadakhel.kodex.jdbc.UpdateBuilder
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.eqColumn
import com.mustafadakhel.kodex.jdbc.inList
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
import kotlinx.datetime.LocalDateTime
import java.sql.SQLException
import java.util.UUID

internal fun databaseUserRepository(db: KodexDatabase, realmId: String): UserRepository =
    JdbcUserRepository(db, realmId)

internal class JdbcUserRepository(
    private val db: KodexDatabase,
    private val realmId: String,
) : UserRepository {

    private val users get() = db.core.users
    private val roles get() = db.core.roles
    private val userRoles get() = db.core.userRoles
    private val profiles get() = db.core.userProfiles
    private val customAttrs get() = db.core.userCustomAttributes

    override fun getAll(): List<UserEntity> = db.transaction {
        select(users)
            .where { users.realmId eq realmId }
            .map { it.toUserEntity() }
    }

    override fun getAllFull(): List<FullUserEntity> = db.transaction {
        val userRows = select(users)
            .where { users.realmId eq realmId }
            .map { it.toUserEntity() }

        if (userRows.isEmpty()) return@transaction emptyList()

        val userIds = userRows.map { it.id }

        val userRolesMap = loadRolesByUserIds(userIds)
        val userProfilesMap = loadProfilesByUserIds(userIds)
        val userAttributesMap = loadCustomAttributesByUserIds(userIds)

        userRows.map { user ->
            FullUserEntity(
                id = user.id,
                createdAt = user.createdAt,
                updatedAt = user.updatedAt,
                phoneNumber = user.phoneNumber,
                email = user.email,
                lastLoggedIn = user.lastLoggedIn,
                status = user.status,
                roles = userRolesMap[user.id] ?: emptyList(),
                profile = userProfilesMap[user.id],
                customAttributes = userAttributesMap[user.id] ?: emptyMap(),
            )
        }
    }

    override fun findById(userId: UUID): UserEntity? = db.transaction {
        select(users)
            .where { (users.id eq userId) and (users.realmId eq realmId) }
            .firstOrNull { it.toUserEntity() }
    }

    override fun findByPhone(phone: String): UserEntity? = db.transaction {
        select(users)
            .where { (users.phoneNumber eq phone) and (users.realmId eq realmId) }
            .firstOrNull { it.toUserEntity() }
    }

    override fun findByEmail(email: String): UserEntity? = db.transaction {
        select(users)
            .where { (users.email eq email) and (users.realmId eq realmId) }
            .firstOrNull { it.toUserEntity() }
    }

    override fun findFullById(userId: UUID): FullUserEntity? = db.transaction {
        val user = select(users)
            .where { (users.id eq userId) and (users.realmId eq realmId) }
            .firstOrNull { it.toUserEntity() }
            ?: return@transaction null

        val rolesForUser = loadRolesForUser(userId)
        val profile = loadProfileForUser(userId)
        val attributes = loadCustomAttributesForUser(userId)

        FullUserEntity(
            id = user.id,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
            phoneNumber = user.phoneNumber,
            email = user.email,
            lastLoggedIn = user.lastLoggedIn,
            status = user.status,
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

                val realmValue = this@JdbcUserRepository.realmId
                val newUserId = insertReturningKey(users, users.id) {
                    this[users.passwordHash] = hashedPassword
                    this[users.createdAt] = currentTime
                    this[users.updatedAt] = currentTime
                    this[users.email] = email
                    this[users.phoneNumber] = phone
                    this[users.realmId] = realmValue
                }

                for (roleName in roleNames) {
                    insertInto(userRoles) {
                        this[userRoles.userId] = newUserId
                        this[userRoles.roleId] = roleIdMap.getValue(roleName)
                    }
                }

                if (profile != null) insertProfile(newUserId, profile)
                if (customAttributes != null) insertCustomAttributes(newUserId, customAttributes)

                val createdUser = select(users)
                    .where { users.id eq newUserId }
                    .firstOrNull { it.toUserEntity() }
                    ?: error("User $newUserId not found after insert")

                UserRepository.CreateUserResult.Success(createdUser)
            }
        } catch (e: SQLException) {
            mapCreateConstraintViolation(e)
        }
    }

    private fun mapCreateConstraintViolation(e: SQLException): UserRepository.CreateUserResult =
        when (detectDuplicateField(e)) {
            DuplicateField.EMAIL -> UserRepository.CreateUserResult.EmailAlreadyExists
            DuplicateField.PHONE -> UserRepository.CreateUserResult.PhoneAlreadyExists
            null -> throw e
        }

    override fun getHashedPassword(userId: UUID): String? = db.transaction {
        select(users)
            .where { (users.id eq userId) and (users.realmId eq realmId) }
            .firstOrNull { it[users.passwordHash] }
    }

    override fun seedRoles(roles: List<Role>): Unit = db.transaction {
        val rolesTable = this@JdbcUserRepository.roles
        val realmValue = this@JdbcUserRepository.realmId
        for (role in roles) {
            upsert(rolesTable, conflictColumns = listOf(rolesTable.name, rolesTable.realmId)) {
                this[rolesTable.name] = role.name
                this[rolesTable.realmId] = realmValue
                this[rolesTable.description] = role.description
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

                update(users) {
                    applyFieldUpdates(this, email, phone, status, currentTime)
                    where { users.id eq userId }
                }

                UserRepository.UpdateUserResult.Success
            }
        } catch (e: SQLException) {
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

        select(userRoles)
            .innerJoin(roles) { userRoles.roleId eqColumn roles.id }
            .where {
                (userRoles.userId eq userId) and
                    (roles.realmId eq realmId)
            }
            .map { it.toRoleEntity() }
    }

    override fun getAllRoles(): List<RoleEntity> = db.transaction {
        select(roles)
            .where { roles.realmId eq realmId }
            .map { it.toRoleEntity() }
    }

    override fun findProfileByUserId(userId: UUID): UserProfileEntity? = db.transaction {
        if (!userExistsInRealm(userId)) return@transaction null

        select(profiles)
            .where { profiles.userId eq userId }
            .firstOrNull { it.toProfileEntity() }
    }

    override fun updateProfileByUserId(userId: UUID, profile: UserProfile): UserRepository.UpdateProfileResult =
        db.transaction {
            val userEntity = select(users)
                .where { (users.id eq userId) and (users.realmId eq realmId) }
                .firstOrNull { it.toUserEntity() }
                ?: return@transaction UserRepository.UpdateProfileResult.NotFound

            upsertProfile(userId, profile)

            UserRepository.UpdateProfileResult.Success(userEntity)
        }

    override fun findCustomAttributesByUserId(userId: UUID): Map<String, String> = db.transaction {
        if (!userExistsInRealm(userId)) return@transaction emptyMap()

        select(customAttrs)
            .where { customAttrs.userId eq userId }
            .map { it[customAttrs.key] to it[customAttrs.value] }
            .toMap()
    }

    override fun replaceAllCustomAttributesByUserId(
        userId: UUID,
        customAttributes: Map<String, String>,
    ): UserRepository.UpdateUserResult = db.transaction {
        if (!userExistsInRealm(userId)) return@transaction UserRepository.UpdateUserResult.NotFound

        validateCustomAttributes(customAttributes)
        deleteFrom(customAttrs).where { customAttrs.userId eq userId }.execute()
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
        val updated = update(users) {
            this[users.lastLoginAt] = loginTime
            where { (users.id eq userId) and (users.realmId eq realmId) }
        }
        updated > 0
    }

    override fun updatePassword(userId: UUID, hashedPassword: String): Boolean = db.transaction {
        val updated = update(users) {
            this[users.passwordHash] = hashedPassword
            where { (users.id eq userId) and (users.realmId eq realmId) }
        }
        updated > 0
    }

    override fun deleteUser(userId: UUID): UserRepository.DeleteResult = db.transaction {
        val deleted = deleteFrom(users)
            .where { (users.id eq userId) and (users.realmId eq realmId) }
            .execute()
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

                update(users) {
                    applyFieldUpdates(this, email, phone, status, currentTime)
                    where { users.id eq userId }
                }

                when (profile) {
                    is FieldUpdate.NoChange -> {}
                    is FieldUpdate.SetValue -> upsertProfile(userId, profile.value)
                    is FieldUpdate.ClearValue -> {
                        deleteFrom(profiles).where { profiles.userId eq userId }.execute()
                    }
                }

                when (customAttributes) {
                    is FieldUpdate.NoChange -> {}
                    is FieldUpdate.SetValue -> upsertCustomAttributes(userId, customAttributes.value)
                    is FieldUpdate.ClearValue -> {
                        deleteFrom(customAttrs).where { customAttrs.userId eq userId }.execute()
                    }
                }

                UserRepository.UpdateUserResult.Success
            }
        } catch (e: SQLException) {
            mapUpdateConstraintViolation(e)
        }
    }

    private fun ConnectionScope.loadRolesByUserIds(
        userIds: List<UUID>,
    ): Map<UUID, List<RoleEntity>> {
        val result = mutableMapOf<UUID, MutableList<RoleEntity>>()
        select(userRoles)
            .innerJoin(roles) { userRoles.roleId eqColumn roles.id }
            .where {
                (userRoles.userId inList userIds) and
                    (roles.realmId eq realmId)
            }
            .map { row ->
                val uid = row[userRoles.userId]
                val role = row.toRoleEntity()
                uid to role
            }
            .forEach { (uid, role) ->
                result.getOrPut(uid) { mutableListOf() }.add(role)
            }
        return result
    }

    private fun ConnectionScope.loadProfilesByUserIds(
        userIds: List<UUID>,
    ): Map<UUID, UserProfileEntity> =
        select(profiles)
            .where { profiles.userId inList userIds }
            .map { row -> row[profiles.userId] to row.toProfileEntity() }
            .toMap()

    private fun ConnectionScope.loadCustomAttributesByUserIds(
        userIds: List<UUID>,
    ): Map<UUID, Map<String, String>> {
        val result = mutableMapOf<UUID, MutableMap<String, String>>()
        select(customAttrs)
            .where { customAttrs.userId inList userIds }
            .map { row ->
                Triple(row[customAttrs.userId], row[customAttrs.key], row[customAttrs.value])
            }
            .forEach { (uid, key, value) ->
                result.getOrPut(uid) { mutableMapOf() }[key] = value
            }
        return result
    }

    private fun ConnectionScope.loadRolesForUser(
        userId: UUID,
    ): List<RoleEntity> =
        select(userRoles)
            .innerJoin(roles) { userRoles.roleId eqColumn roles.id }
            .where {
                (userRoles.userId eq userId) and
                    (roles.realmId eq realmId)
            }
            .map { it.toRoleEntity() }

    private fun ConnectionScope.loadProfileForUser(
        userId: UUID,
    ): UserProfileEntity? =
        select(profiles)
            .where { profiles.userId eq userId }
            .firstOrNull { it.toProfileEntity() }

    private fun ConnectionScope.loadCustomAttributesForUser(
        userId: UUID,
    ): Map<String, String> =
        select(customAttrs)
            .where { customAttrs.userId eq userId }
            .map { it[customAttrs.key] to it[customAttrs.value] }
            .toMap()

    private fun ConnectionScope.insertProfile(
        userId: UUID,
        profile: UserProfile,
    ) {
        insertInto(profiles) {
            this[profiles.userId] = userId
            setProfileFields(this, profile)
        }
    }

    private fun ConnectionScope.upsertProfile(
        userId: UUID,
        profile: UserProfile,
    ) {
        val updated = update(profiles) {
            setProfileFields(this, profile)
            where { profiles.userId eq userId }
        }
        if (updated == 0) insertProfile(userId, profile)
    }

    private fun setProfileFields(stmt: InsertBuilder, profile: UserProfile) {
        stmt[profiles.firstName] = profile.firstName
        stmt[profiles.lastName] = profile.lastName
        stmt[profiles.address] = profile.address
        stmt[profiles.profilePicture] = profile.profilePicture
    }

    private fun setProfileFields(stmt: UpdateBuilder, profile: UserProfile) {
        stmt[profiles.firstName] = profile.firstName
        stmt[profiles.lastName] = profile.lastName
        stmt[profiles.address] = profile.address
        stmt[profiles.profilePicture] = profile.profilePicture
    }

    private fun ConnectionScope.insertCustomAttributes(
        userId: UUID,
        attributes: Map<String, String>,
    ) {
        validateCustomAttributes(attributes)
        for ((attrKey, attrValue) in attributes) {
            insertInto(customAttrs) {
                this[customAttrs.userId] = userId
                this[customAttrs.key] = attrKey
                this[customAttrs.value] = attrValue
            }
        }
    }

    private fun ConnectionScope.upsertCustomAttributes(
        userId: UUID,
        attributes: Map<String, String>,
    ) {
        val existing = select(customAttrs)
            .where { customAttrs.userId eq userId }
            .map { it[customAttrs.key] to it[customAttrs.id] }
            .toMap()

        val newKeysCount = attributes.keys.count { it !in existing }
        val totalAfterUpdate = existing.size + newKeysCount
        require(totalAfterUpdate <= MAX_ATTRIBUTES_PER_USER) {
            "Too many custom attributes after update (max: $MAX_ATTRIBUTES_PER_USER, would be: $totalAfterUpdate)"
        }

        for ((attrKey, attrValue) in attributes) {
            validateKey(attrKey)
            validateValue(attrKey, attrValue)
            val existingId = existing[attrKey]
            if (existingId != null) {
                update(customAttrs) {
                    this[customAttrs.value] = attrValue
                    where { customAttrs.id eq existingId }
                }
            } else {
                insertInto(customAttrs) {
                    this[customAttrs.userId] = userId
                    this[customAttrs.key] = attrKey
                    this[customAttrs.value] = attrValue
                }
            }
        }
    }

    private fun ConnectionScope.replaceRolesForUser(
        userId: UUID,
        roleNames: List<String>,
    ): UserRepository.UpdateRolesResult {
        if (!userExistsInRealm(userId)) return UserRepository.UpdateRolesResult.UserNotFound

        val invalidRole = findFirstInvalidRole(roleNames)
        if (invalidRole != null) return UserRepository.UpdateRolesResult.InvalidRole(invalidRole)

        val roleIdMap = lookupRoleIds(roleNames)

        deleteFrom(userRoles).where { userRoles.userId eq userId }.execute()

        for (roleName in roleNames) {
            insertInto(userRoles) {
                this[userRoles.userId] = userId
                this[userRoles.roleId] = roleIdMap.getValue(roleName)
            }
        }
        return UserRepository.UpdateRolesResult.Success
    }

    private fun ConnectionScope.userExistsInRealm(userId: UUID): Boolean =
        select(users)
            .where { (users.id eq userId) and (users.realmId eq realmId) }
            .any()

    private fun ConnectionScope.findFirstInvalidRole(
        roleNames: List<String>,
    ): String? {
        if (roleNames.isEmpty()) return null
        val existingRoles = select(roles)
            .where { (roles.name inList roleNames) and (roles.realmId eq realmId) }
            .map { it[roles.name] }
            .toSet()
        return roleNames.firstOrNull { it !in existingRoles }
    }

    private fun ConnectionScope.lookupRoleIds(
        roleNames: List<String>,
    ): Map<String, UUID> =
        select(roles)
            .where { (roles.name inList roleNames) and (roles.realmId eq realmId) }
            .map { it[roles.name] to it[roles.id] }
            .toMap()

    private fun mapUpdateConstraintViolation(e: SQLException): UserRepository.UpdateUserResult =
        when (detectDuplicateField(e)) {
            DuplicateField.EMAIL -> UserRepository.UpdateUserResult.EmailAlreadyExists
            DuplicateField.PHONE -> UserRepository.UpdateUserResult.PhoneAlreadyExists
            null -> throw e
        }

    private fun detectDuplicateField(e: SQLException): DuplicateField? {
        val matchedIndex = ConstraintViolationMapper.detectDuplicateIndex(
            e,
            users.emailRealmIndex,
            users.phoneRealmIndex,
        ) ?: return null
        return when (matchedIndex) {
            users.emailRealmIndex -> DuplicateField.EMAIL
            users.phoneRealmIndex -> DuplicateField.PHONE
            else -> null
        }
    }

    private enum class DuplicateField { EMAIL, PHONE }

    private fun applyFieldUpdates(
        stmt: UpdateBuilder,
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

    private fun Row.toUserEntity() = UserEntity(
        id = this[users.id],
        createdAt = this[users.createdAt],
        updatedAt = this[users.updatedAt],
        phoneNumber = this[users.phoneNumber],
        email = this[users.email],
        lastLoggedIn = this[users.lastLoginAt],
        status = this[users.status],
    )

    private fun Row.toProfileEntity() = UserProfileEntity(
        userId = this[profiles.userId],
        firstName = this[profiles.firstName],
        lastName = this[profiles.lastName],
        address = this[profiles.address],
        profilePicture = this[profiles.profilePicture],
    )

    private fun Row.toRoleEntity() = RoleEntity(
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
