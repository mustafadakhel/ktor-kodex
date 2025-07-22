package com.mustafadakhel.kodex.repository

import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.database.FullUserEntity
import com.mustafadakhel.kodex.model.database.RoleEntity
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.model.database.UserProfileEntity
import kotlinx.datetime.LocalDateTime
import java.util.*

internal interface UserRepository {

    fun getAll(): List<UserEntity>

    fun findById(userId: UUID): UserEntity?

    fun findByPhone(phone: String): UserEntity?

    fun findByEmail(email: String): UserEntity?

    fun findFullById(userId: UUID): FullUserEntity?

    fun emailExists(email: String): Boolean

    fun phoneExists(phone: String): Boolean

    fun create(
        email: String? = null,
        phone: String? = null,
        hashedPassword: String,
        roleNames: List<String>,
        customAttributes: Map<String, String>? = null,
        profile: UserProfile? = null,
        currentTime: LocalDateTime,
    ) : CreateUserResult

    fun updateById(
        userId: UUID,
        email: String? = null,
        phone: String? = null,
        currentTime: LocalDateTime,
    ): UpdateUserResult

    fun seedRoles(roles: List<Role>)

    fun findRoles(userId: UUID): List<RoleEntity>

    fun getAllRoles(): List<RoleEntity>

    fun updateRolesForUser(
        userId: UUID,
        roleNames: List<String>,
    ): UpdateRolesResult

    fun authenticate(
        userId: UUID,
        hashedPassword: String,
    ): Boolean

    fun findProfileByUserId(userId: UUID): UserProfileEntity?

    fun updateProfileByUserId(userId: UUID, profile: UserProfile): Boolean

    fun findCustomAttributesByUserId(userId: UUID): Map<String, String>

    fun replaceAllCustomAttributesByUserId(userId: UUID, customAttributes: Map<String, String>): UpdateUserResult

    fun updateCustomAttributesByUserId(userId: UUID, customAttributes: Map<String, String>): UpdateUserResult

    fun setVerified(userId: UUID, verified: Boolean): Boolean

    sealed interface CreateResult {
        sealed interface Duplicate : CreateResult
        sealed interface UnexpectedError : CreateResult
    }

    sealed interface CreateUserResult : CreateResult {
        data class Success(val user: UserEntity) : CreateUserResult
        data object EmailAlreadyExists : CreateUserResult, CreateResult.Duplicate
        data object PhoneAlreadyExists : CreateUserResult, CreateResult.Duplicate
        data class InvalidRole(val roleName: String) : CreateUserResult, CreateResult.Duplicate
    }

    sealed interface UpdateResult {
        sealed interface NotFound : UpdateResult
        sealed interface Duplicate : UpdateResult
    }

    sealed interface UpdateUserResult : UpdateResult {
        data object Success : UpdateUserResult
        object NotFound : UpdateUserResult, UpdateResult.NotFound
        data object EmailAlreadyExists : UpdateUserResult, UpdateResult.Duplicate
        data object PhoneAlreadyExists : UpdateUserResult, UpdateResult.Duplicate
        data class InvalidRole(val roleName: String) : UpdateUserResult, UpdateResult.NotFound
    }

    sealed interface UpdateProfileResult : UpdateResult {
        data class Success(val user: UserEntity) : UpdateProfileResult
        data object NotFound : UpdateProfileResult, UpdateResult.NotFound
    }

    sealed interface UpdateRolesResult : UpdateResult {
        data object Success : UpdateRolesResult
        data class InvalidRole(val roleName: String) : UpdateRolesResult, UpdateResult.NotFound
    }

    sealed interface UpdateCustomAttributesResult : UpdateResult {
        data class Success(val user: UserEntity) : UpdateCustomAttributesResult
        data object NotFound : UpdateCustomAttributesResult, UpdateResult.NotFound
    }

    sealed interface DeleteResult {
        object NotFound : DeleteResult
        object Success : DeleteResult
    }
}
