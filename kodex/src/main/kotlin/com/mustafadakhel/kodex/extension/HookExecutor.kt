package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.audit.AuditEvent
import com.mustafadakhel.kodex.model.UserProfile
import java.util.UUID

/**
 * Executes hooks from registered extensions in a chained manner.
 * Supports multiple extensions of the same type, executing them in registration order.
 */
internal class HookExecutor(private val registry: ExtensionRegistry) {

    /**
     * Executes beforeUserCreate hooks from all UserLifecycleHooks extensions.
     * Each extension receives the output of the previous extension (chaining).
     */
    suspend fun executeBeforeUserCreate(
        email: String?,
        phone: String?,
        password: String,
        customAttributes: Map<String, String>?,
        profile: UserProfile?
    ): UserCreateData {
        var current = UserCreateData(email, phone, customAttributes, profile)

        registry.getAllOfType(UserLifecycleHooks::class).forEach { hook ->
            current = hook.beforeUserCreate(
                current.email,
                current.phone,
                password,
                current.customAttributes,
                current.profile
            )
        }

        return current
    }

    /**
     * Executes beforeUserUpdate hooks from all UserLifecycleHooks extensions.
     * Each extension receives the output of the previous extension (chaining).
     */
    suspend fun executeBeforeUserUpdate(
        userId: UUID,
        email: String?,
        phone: String?
    ): UserUpdateData {
        var current = UserUpdateData(email, phone)

        registry.getAllOfType(UserLifecycleHooks::class).forEach { hook ->
            current = hook.beforeUserUpdate(userId, current.email, current.phone)
        }

        return current
    }

    /**
     * Executes beforeProfileUpdate hooks from all UserLifecycleHooks extensions.
     * Each extension receives the output of the previous extension (chaining).
     */
    suspend fun executeBeforeProfileUpdate(
        userId: UUID,
        firstName: String?,
        lastName: String?,
        address: String?,
        profilePicture: String?
    ): UserProfileUpdateData {
        var current = UserProfileUpdateData(firstName, lastName, address, profilePicture)

        registry.getAllOfType(UserLifecycleHooks::class).forEach { hook ->
            current = hook.beforeProfileUpdate(
                userId,
                current.firstName,
                current.lastName,
                current.address,
                current.profilePicture
            )
        }

        return current
    }

    /**
     * Executes beforeCustomAttributesUpdate hooks from all UserLifecycleHooks extensions.
     * Each extension receives the output of the previous extension (chaining).
     */
    suspend fun executeBeforeCustomAttributesUpdate(
        userId: UUID,
        customAttributes: Map<String, String>
    ): Map<String, String> {
        var current = customAttributes

        registry.getAllOfType(UserLifecycleHooks::class).forEach { hook ->
            current = hook.beforeCustomAttributesUpdate(userId, current)
        }

        return current
    }

    /**
     * Executes beforeLogin hooks from all UserLifecycleHooks extensions.
     * Each extension receives the output of the previous extension (chaining).
     */
    suspend fun executeBeforeLogin(identifier: String): String {
        var current = identifier

        registry.getAllOfType(UserLifecycleHooks::class).forEach { hook ->
            current = hook.beforeLogin(current)
        }

        return current
    }

    /**
     * Executes afterLoginFailure hooks from all UserLifecycleHooks extensions.
     * All extensions receive the same identifier.
     */
    suspend fun executeAfterLoginFailure(identifier: String) {
        registry.getAllOfType(UserLifecycleHooks::class).forEach { hook ->
            hook.afterLoginFailure(identifier)
        }
    }

    /**
     * Executes onAuditEvent hooks from all AuditHooks extensions.
     * All extensions receive the same event.
     */
    suspend fun executeAuditEvent(event: AuditEvent) {
        registry.getAllOfType(AuditHooks::class).forEach { hook ->
            hook.onAuditEvent(event)
        }
    }
}
