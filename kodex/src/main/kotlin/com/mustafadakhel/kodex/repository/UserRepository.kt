package com.mustafadakhel.kodex.repository

import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.FullUserEntity
import com.mustafadakhel.kodex.model.database.RoleEntity
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.model.database.UserProfileEntity
import com.mustafadakhel.kodex.update.FieldUpdate
import kotlinx.datetime.LocalDateTime
import java.util.*

public interface UserRepository {

    public fun getAll(realmId: String): List<UserEntity>

    public fun getAllFull(realmId: String): List<FullUserEntity>

    public fun findById(userId: UUID, realmId: String): UserEntity?

    public fun findByPhone(phone: String, realmId: String): UserEntity?

    public fun findByEmail(email: String, realmId: String): UserEntity?

    public fun findFullById(userId: UUID, realmId: String): FullUserEntity?

    public fun create(
        email: String? = null,
        phone: String? = null,
        hashedPassword: String,
        roleNames: List<String>,
        customAttributes: Map<String, String>? = null,
        profile: UserProfile? = null,
        currentTime: LocalDateTime,
        realmId: String,
    ) : CreateUserResult

    public fun updateById(
        userId: UUID,
        realmId: String,
        email: FieldUpdate<String> = FieldUpdate.NoChange,
        phone: FieldUpdate<String> = FieldUpdate.NoChange,
        status: FieldUpdate<UserStatus> = FieldUpdate.NoChange,
        currentTime: LocalDateTime,
    ): UpdateUserResult

    public fun seedRoles(roles: List<Role>)

    public fun findRoles(userId: UUID, realmId: String): List<RoleEntity>

    public fun getAllRoles(): List<RoleEntity>

    public fun updateRolesForUser(
        userId: UUID,
        realmId: String,
        roleNames: List<String>,
    ): UpdateRolesResult

    public fun getHashedPassword(
        userId: UUID,
        realmId: String,
    ): String?

    public fun findProfileByUserId(userId: UUID, realmId: String): UserProfileEntity?

    public fun updateProfileByUserId(userId: UUID, realmId: String, profile: UserProfile): UpdateProfileResult

    public fun findCustomAttributesByUserId(userId: UUID, realmId: String): Map<String, String>

    public fun replaceAllCustomAttributesByUserId(userId: UUID, realmId: String, customAttributes: Map<String, String>): UpdateUserResult

    public fun updateCustomAttributesByUserId(userId: UUID, realmId: String, customAttributes: Map<String, String>): UpdateUserResult

    public fun updateLastLogin(userId: UUID, realmId: String, loginTime: LocalDateTime): Boolean

    public fun updatePassword(userId: UUID, realmId: String, hashedPassword: String): Boolean

    public fun deleteUser(userId: UUID, realmId: String): DeleteResult

    /** Updates multiple user fields atomically in one transaction. */
    public fun updateBatch(
        userId: UUID,
        realmId: String,
        email: FieldUpdate<String> = FieldUpdate.NoChange,
        phone: FieldUpdate<String> = FieldUpdate.NoChange,
        status: FieldUpdate<UserStatus> = FieldUpdate.NoChange,
        profile: FieldUpdate<UserProfile> = FieldUpdate.NoChange,
        customAttributes: FieldUpdate<Map<String, String>> = FieldUpdate.NoChange,
        currentTime: LocalDateTime
    ): UpdateUserResult

    public sealed interface CreateResult {
        public sealed interface Duplicate : CreateResult
        public sealed interface UnexpectedError : CreateResult
    }

    public sealed interface CreateUserResult : CreateResult {
        public data class Success(val user: UserEntity) : CreateUserResult
        public data object EmailAlreadyExists : CreateUserResult, CreateResult.Duplicate
        public data object PhoneAlreadyExists : CreateUserResult, CreateResult.Duplicate
        public data class InvalidRole(val roleName: String) : CreateUserResult, CreateResult.Duplicate
    }

    public sealed interface UpdateResult {
        public sealed interface NotFound : UpdateResult
        public sealed interface Duplicate : UpdateResult
    }

    public sealed interface UpdateUserResult : UpdateResult {
        public data object Success : UpdateUserResult
        public object NotFound : UpdateUserResult, UpdateResult.NotFound
        public data object EmailAlreadyExists : UpdateUserResult, UpdateResult.Duplicate
        public data object PhoneAlreadyExists : UpdateUserResult, UpdateResult.Duplicate
        public data class InvalidRole(val roleName: String) : UpdateUserResult, UpdateResult.NotFound
    }

    public sealed interface UpdateProfileResult : UpdateResult {
        public data class Success(val user: UserEntity) : UpdateProfileResult
        public data object NotFound : UpdateProfileResult, UpdateResult.NotFound
    }

    public sealed interface UpdateRolesResult : UpdateResult {
        public data object Success : UpdateRolesResult
        public data class InvalidRole(val roleName: String) : UpdateRolesResult, UpdateResult.NotFound
    }

    public sealed interface DeleteResult {
        public object NotFound : DeleteResult
        public object Success : DeleteResult
    }
}
