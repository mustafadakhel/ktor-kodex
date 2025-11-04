package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.UserProfile
import java.util.UUID

/** Hook interface for user lifecycle events. */
public interface UserLifecycleHooks : RealmExtension {

    /** Called before user creation. Can validate or transform data. */
    public suspend fun beforeUserCreate(
        email: String?,
        phone: String?,
        password: String,
        customAttributes: Map<String, String>?,
        profile: UserProfile?
    ): UserCreateData {
        return UserCreateData(email, phone, customAttributes, profile)
    }

    /** Called before user update. Can validate or transform data. */
    public suspend fun beforeUserUpdate(
        userId: UUID,
        email: String?,
        phone: String?
    ): UserUpdateData {
        return UserUpdateData(email, phone)
    }

    /** Called before profile update. Can validate or transform data. */
    public suspend fun beforeProfileUpdate(
        userId: UUID,
        firstName: String?,
        lastName: String?,
        address: String?,
        profilePicture: String?
    ): UserProfileUpdateData {
        return UserProfileUpdateData(firstName, lastName, address, profilePicture)
    }

    /** Called before custom attributes update. */
    public suspend fun beforeCustomAttributesUpdate(
        userId: UUID,
        customAttributes: Map<String, String>
    ): Map<String, String> {
        return customAttributes
    }

    /** Called before login. Can check account status or transform identifier. */
    public suspend fun beforeLogin(identifier: String, metadata: LoginMetadata): String {
        return identifier
    }

    /** Called after failed login attempt. */
    public suspend fun afterLoginFailure(
        identifier: String,
        userId: UUID?,
        identifierType: String,
        metadata: LoginMetadata
    ) {
    }

    /** Called after successful authentication, before token generation. Extensions can check user state and throw to block login. */
    public suspend fun afterAuthentication(userId: UUID) {
    }

    /** Called before user deletion. Extensions can perform cleanup (e.g., anonymize audit logs). */
    public suspend fun beforeUserDelete(userId: UUID) {
    }
}

public data class UserCreateData(
    val email: String?,
    val phone: String?,
    val customAttributes: Map<String, String>?,
    val profile: UserProfile?
)

public data class UserUpdateData(
    val email: String?,
    val phone: String?
)

public data class UserProfileUpdateData(
    val firstName: String?,
    val lastName: String?,
    val address: String?,
    val profilePicture: String?
)

public data class LoginMetadata(
    val ipAddress: String,
    val userAgent: String?
)
