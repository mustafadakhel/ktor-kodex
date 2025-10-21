package com.mustafadakhel.kodex.repository.database

import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.*
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.update.FieldUpdate
import com.mustafadakhel.kodex.util.exposedTransaction
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import java.util.*

internal fun databaseUserRepository(): UserRepository = ExposedUserRepository

private object ExposedUserRepository : UserRepository {

    override fun getAll(): List<UserEntity> = exposedTransaction {
        UserDao.all().map { it.toEntity() }
    }

    override fun getAllFull(): List<FullUserEntity> = exposedTransaction {
        val users = UserDao.all().toList()

        if (users.isEmpty()) return@exposedTransaction emptyList()

        val userIds = users.map { it.id.value }

        // Batch load all user roles in a single query
        val userRolesMap = mutableMapOf<UUID, MutableList<RoleEntity>>()
        UserRoles
            .innerJoin(Roles)
            .select(UserRoles.userId, Roles.id, Roles.description)
            .where { UserRoles.userId inList userIds }
            .forEach { row ->
                val userId = row[UserRoles.userId].value
                val role = RoleEntity(
                    name = row[Roles.id].value,
                    description = row[Roles.description]
                )
                userRolesMap.getOrPut(userId) { mutableListOf() }.add(role)
            }

        // Batch load all profiles in a single query
        val userProfilesMap = UserProfileDao
            .find { UserProfiles.id inList userIds }
            .associate { it.id.value to it.toEntity() }

        // Batch load all custom attributes in a single query
        val userAttributesMap = UserCustomAttributesDao
            .find { UserCustomAttributes.userId inList userIds }
            .groupBy({ it.userId.value }) { it.key to it.value }
            .mapValues { (_, pairs) -> pairs.toMap() }

        // Construct FullUserEntity objects with preloaded data
        users.map { user ->
            FullUserEntity(
                id = user.id.value,
                createdAt = user.createdAt,
                updatedAt = user.updatedAt,
                isVerified = user.isVerified,
                phoneNumber = user.phoneNumber,
                email = user.email,
                lastLoggedIn = user.lastLoginAt,
                status = user.status,
                roles = userRolesMap[user.id.value] ?: emptyList(),
                profile = userProfilesMap[user.id.value],
                customAttributes = userAttributesMap[user.id.value] ?: emptyMap()
            )
        }
    }

    override fun findById(userId: UUID): UserEntity? = exposedTransaction {
        UserDao.findById(userId)?.toEntity()
    }

    override fun findByPhone(phone: String): UserEntity? = exposedTransaction {
        UserDao.find { Users.phoneNumber eq phone }.firstOrNull()?.toEntity()
    }

    override fun findByEmail(email: String): UserEntity? = exposedTransaction {
        UserDao.find { Users.email eq email }.firstOrNull()?.toEntity()
    }

    override fun findFullById(userId: UUID): FullUserEntity? = exposedTransaction {
        UserDao.findById(userId)?.toFullEntity()
    }

    override fun create(
        email: String?,
        phone: String?,
        hashedPassword: String,
        roleNames: List<String>,
        customAttributes: Map<String, String>?,
        profile: UserProfile?,
        currentTime: LocalDateTime,
    ) = exposedTransaction {
        if (email != null && UserDao.find { Users.email eq email }.any()) {
            return@exposedTransaction UserRepository.CreateUserResult.EmailAlreadyExists
        }
        if (phone != null && UserDao.find { Users.phoneNumber eq phone }.any()) {
            return@exposedTransaction UserRepository.CreateUserResult.PhoneAlreadyExists
        }

        val newUser = UserDao.new {
            this.passwordHash = hashedPassword
            this.updatedAt = currentTime
            this.createdAt = currentTime
            this.email = email
            this.phoneNumber = phone
        }

        val rolesResult = updateRolesForUserInternal(newUser.id.value, roleNames)
        if (rolesResult is UserRepository.UpdateRolesResult.InvalidRole) {
            return@exposedTransaction UserRepository.CreateUserResult.InvalidRole(rolesResult.roleName)
        }

        if (profile != null)
            createProfile(newUser.id.value, profile)

        if (customAttributes != null)
            createCustomAttributes(newUser.id.value, customAttributes)

        UserRepository.CreateUserResult.Success(newUser.toEntity())
    }

    override fun getHashedPassword(userId: UUID): String? = exposedTransaction {
        UserDao.findById(userId)?.passwordHash
    }

    override fun seedRoles(roles: List<Role>) = exposedTransaction {
        UserRoles.deleteAll()
        RoleDao.all().forEach {
            it.delete()
        }
        roles.forEach { role ->
            RoleDao.new(role.name) {
                description = role.description
            }
        }
    }

    override fun updateById(
        userId: UUID,
        email: FieldUpdate<String>,
        phone: FieldUpdate<String>,
        isVerified: FieldUpdate<Boolean>,
        status: FieldUpdate<UserStatus>,
        currentTime: LocalDateTime
    ): UserRepository.UpdateUserResult = exposedTransaction {
        val user = UserDao.findById(userId) ?: run {
            return@exposedTransaction UserRepository.UpdateUserResult.NotFound
        }

        when (email) {
            is FieldUpdate.NoChange -> { /* no change */ }
            is FieldUpdate.SetValue -> {
                if (user.email != email.value) {
                    if (UserDao.find { Users.email eq email.value }.any()) {
                        return@exposedTransaction UserRepository.UpdateUserResult.EmailAlreadyExists
                    }
                    user.email = email.value
                }
            }
            is FieldUpdate.ClearValue -> {
                user.email = null
            }
        }

        when (phone) {
            is FieldUpdate.NoChange -> { /* no change */ }
            is FieldUpdate.SetValue -> {
                if (user.phoneNumber != phone.value) {
                    if (UserDao.find { Users.phoneNumber eq phone.value }.any()) {
                        return@exposedTransaction UserRepository.UpdateUserResult.PhoneAlreadyExists
                    }
                    user.phoneNumber = phone.value
                }
            }
            is FieldUpdate.ClearValue -> {
                user.phoneNumber = null
            }
        }

        when (isVerified) {
            is FieldUpdate.NoChange -> { /* no change */ }
            is FieldUpdate.SetValue -> {
                user.isVerified = isVerified.value
            }
            is FieldUpdate.ClearValue -> {
                // isVerified is non-nullable, so ClearValue doesn't make sense
                // but we handle it for completeness
            }
        }

        when (status) {
            is FieldUpdate.NoChange -> { /* no change */ }
            is FieldUpdate.SetValue -> {
                user.status = status.value
            }
            is FieldUpdate.ClearValue -> {
                // status is non-nullable, so ClearValue doesn't make sense
                // but we handle it for completeness
            }
        }

        user.updatedAt = currentTime

        UserRepository.UpdateUserResult.Success
    }

    private fun createCustomAttributes(newUserId: UUID, customAttributes: Map<String, String>) {
        UserCustomAttributesDao.createForUser(newUserId, customAttributes)
    }

    private fun updateRolesForUserInternal(
        userId: UUID,
        roleNames: List<String>
    ): UserRepository.UpdateRolesResult {
        UserRoles.deleteWhere { UserRoles.userId eq userId }
        roleNames.forEach { roleName ->
            RoleDao.findById(roleName)
                ?: return UserRepository.UpdateRolesResult.InvalidRole(roleName)
            UserRoles.insert {
                it[UserRoles.userId] = userId
                it[UserRoles.roleId] = roleName
            }
        }
        return UserRepository.UpdateRolesResult.Success
    }

    override fun updateRolesForUser(
        userId: UUID,
        roleNames: List<String>
    ): UserRepository.UpdateRolesResult = exposedTransaction {
        updateRolesForUserInternal(userId, roleNames)
    }

    private fun createProfile(newUserId: UUID, profile: UserProfile) {
        UserProfileDao.new(newUserId) {
            this.firstName = profile.firstName
            this.lastName = profile.lastName
            this.address = profile.address
            this.profilePicture = profile.profilePicture
        }
    }

    override fun findRoles(userId: UUID): List<RoleEntity> = exposedTransaction {
        UserDao.findById(userId)?.roles?.map { it.toEntity() }?.toList()
            ?: emptyList()
    }

    override fun getAllRoles(): List<RoleEntity> = exposedTransaction {
        RoleDao.all().map { it.toEntity() }
    }

    override fun findProfileByUserId(userId: UUID): UserProfileEntity? = exposedTransaction {
        UserProfileDao.findById(userId)?.toEntity()
    }

    override fun updateProfileByUserId(userId: UUID, profile: UserProfile) = exposedTransaction {
        val profileDao = UserProfileDao.findByIdAndUpdate(userId) {
            it.firstName = profile.firstName
            it.lastName = profile.lastName
            it.address = profile.address
            it.profilePicture = profile.profilePicture
        }

        if (profileDao != null) {
            val userDao = UserDao.findById(userId)
            if (userDao != null) {
                UserRepository.UpdateProfileResult.Success(userDao.toEntity())
            } else {
                UserRepository.UpdateProfileResult.NotFound
            }
        } else {
            UserRepository.UpdateProfileResult.NotFound
        }
    }

    override fun findCustomAttributesByUserId(userId: UUID): Map<String, String> = exposedTransaction {
        UserCustomAttributesDao.findByUserId(userId).associate { it.key to it.value }
    }

    override fun replaceAllCustomAttributesByUserId(
        userId: UUID, customAttributes: Map<String, String>
    ) = exposedTransaction {
        UserDao.findById(userId) ?: run { return@exposedTransaction UserRepository.UpdateUserResult.NotFound }

        UserCustomAttributesDao.replaceAllForUser(userId, customAttributes)
        UserRepository.UpdateUserResult.Success
    }

    override fun updateCustomAttributesByUserId(
        userId: UUID,
        customAttributes: Map<String, String>
    ) = exposedTransaction {
        UserDao.findById(userId) ?: run { return@exposedTransaction UserRepository.UpdateUserResult.NotFound }

        UserCustomAttributesDao.updateForUser(userId, customAttributes)
        UserRepository.UpdateUserResult.Success
    }

    override fun setVerified(userId: UUID, verified: Boolean) = exposedTransaction {
        UserDao.findById(userId)?.let {
            it.isVerified = verified
            true
        } ?: false
    }

    override fun updateLastLogin(userId: UUID, loginTime: LocalDateTime) = exposedTransaction {
        UserDao.findById(userId)?.let {
            it.lastLoginAt = loginTime
            true
        } ?: false
    }

    override fun updatePassword(userId: UUID, hashedPassword: String) = exposedTransaction {
        UserDao.findById(userId)?.let {
            it.passwordHash = hashedPassword
            true
        } ?: false
    }

    override fun updateBatch(
        userId: UUID,
        email: FieldUpdate<String>,
        phone: FieldUpdate<String>,
        isVerified: FieldUpdate<Boolean>,
        status: FieldUpdate<UserStatus>,
        profile: FieldUpdate<UserProfile>,
        customAttributes: FieldUpdate<Map<String, String>>,
        currentTime: LocalDateTime
    ): UserRepository.UpdateUserResult = exposedTransaction {
        // All operations in single transaction - all succeed or all fail
        val user = UserDao.findById(userId) ?: run {
            return@exposedTransaction UserRepository.UpdateUserResult.NotFound
        }

        // Update user fields
        when (email) {
            is FieldUpdate.NoChange -> { /* no change */ }
            is FieldUpdate.SetValue -> {
                if (user.email != email.value) {
                    if (UserDao.find { Users.email eq email.value }.any()) {
                        return@exposedTransaction UserRepository.UpdateUserResult.EmailAlreadyExists
                    }
                    user.email = email.value
                }
            }
            is FieldUpdate.ClearValue -> {
                user.email = null
            }
        }

        when (phone) {
            is FieldUpdate.NoChange -> { /* no change */ }
            is FieldUpdate.SetValue -> {
                if (user.phoneNumber != phone.value) {
                    if (UserDao.find { Users.phoneNumber eq phone.value }.any()) {
                        return@exposedTransaction UserRepository.UpdateUserResult.PhoneAlreadyExists
                    }
                    user.phoneNumber = phone.value
                }
            }
            is FieldUpdate.ClearValue -> {
                user.phoneNumber = null
            }
        }

        when (isVerified) {
            is FieldUpdate.NoChange -> { /* no change */ }
            is FieldUpdate.SetValue -> {
                user.isVerified = isVerified.value
            }
            is FieldUpdate.ClearValue -> { /* isVerified is non-nullable */ }
        }

        when (status) {
            is FieldUpdate.NoChange -> { /* no change */ }
            is FieldUpdate.SetValue -> {
                user.status = status.value
            }
            is FieldUpdate.ClearValue -> { /* status is non-nullable */ }
        }

        user.updatedAt = currentTime

        // Update profile if provided
        when (profile) {
            is FieldUpdate.NoChange -> { /* no change */ }
            is FieldUpdate.SetValue -> {
                UserProfileDao.findByIdAndUpdate(userId) { profileDao ->
                    profileDao.firstName = profile.value.firstName
                    profileDao.lastName = profile.value.lastName
                    profileDao.address = profile.value.address
                    profileDao.profilePicture = profile.value.profilePicture
                }
            }
            is FieldUpdate.ClearValue -> {
                // Profile clearing would require deleting the profile row
                UserProfileDao.findById(userId)?.delete()
            }
        }

        // Update custom attributes if provided
        when (customAttributes) {
            is FieldUpdate.NoChange -> { /* no change */ }
            is FieldUpdate.SetValue -> {
                UserCustomAttributesDao.updateForUser(userId, customAttributes.value)
            }
            is FieldUpdate.ClearValue -> {
                // Clear all custom attributes
                UserCustomAttributesDao.replaceAllForUser(userId, emptyMap())
            }
        }

        UserRepository.UpdateUserResult.Success
    }

    private fun RoleDao.toEntity() = RoleEntity(
        name = name.value,
        description = description
    )

    private fun UserDao.toEntity(): UserEntity = UserEntity(
        id = id.value,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isVerified = isVerified,
        phoneNumber = phoneNumber,
        email = email,
        lastLoggedIn = lastLoginAt,
        status = status,
    )

    private fun UserDao.toFullEntity() = FullUserEntity(
        id = id.value,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isVerified = isVerified,
        phoneNumber = phoneNumber,
        email = email,
        lastLoggedIn = lastLoginAt,
        status = status,
        roles = allRoles().map { RoleEntity(it.name.value, it.description) },
        profile = profile?.toEntity(),
        customAttributes = allCustomAttributes(),
    )

    private fun UserProfileDao.toEntity() = UserProfileEntity(
        userId = id.value,
        firstName = firstName,
        lastName = lastName,
        address = address,
        profilePicture = profilePicture,
    )
}
