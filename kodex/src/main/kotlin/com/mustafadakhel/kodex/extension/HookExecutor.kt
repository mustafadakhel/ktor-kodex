package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.UserProfile
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Executes hooks from registered extensions in a chained manner.
 * Supports multiple extensions of the same type, executing them in priority order.
 *
 * Lower priority extensions run first (e.g., priority 10 runs before priority 100).
 *
 * @param registry Extension registry containing all registered extensions
 * @param failureStrategy Strategy for handling hook execution failures
 */
internal class HookExecutor(
    private val registry: ExtensionRegistry,
    private val failureStrategy: HookFailureStrategy = HookFailureStrategy.FAIL_FAST
) {
    private val logger = LoggerFactory.getLogger(HookExecutor::class.java)

    suspend fun executeBeforeUserCreate(
        email: String?,
        phone: String?,
        password: String,
        customAttributes: Map<String, String>?,
        profile: UserProfile?
    ): UserCreateData {
        var current = UserCreateData(email, phone, customAttributes, profile)
        val failures = mutableListOf<HookFailure>()

        // Execute hooks in priority order (lower values runs first)
        registry.getAllOfType(UserLifecycleHooks::class)
            .sortedBy { it.priority }
            .forEach { hook ->
                when (failureStrategy) {
                    HookFailureStrategy.FAIL_FAST -> {
                        current = hook.beforeUserCreate(
                            current.email,
                            current.phone,
                            password,
                            current.customAttributes,
                            current.profile
                        )
                    }
                    HookFailureStrategy.COLLECT_ERRORS -> {
                        try {
                            current = hook.beforeUserCreate(
                                current.email,
                                current.phone,
                                password,
                                current.customAttributes,
                                current.profile
                            )
                        } catch (e: Throwable) {
                            // Collect error but continue processing remaining hooks
                            failures.add(HookFailure(hook::class.simpleName ?: "Unknown", e))
                        }
                    }
                    HookFailureStrategy.SKIP_FAILED -> {
                        try {
                            current = hook.beforeUserCreate(
                                current.email,
                                current.phone,
                                password,
                                current.customAttributes,
                                current.profile
                            )
                        } catch (e: Throwable) {
                            logger.warn("Hook ${hook::class.simpleName} failed in beforeUserCreate", e)
                        }
                    }
                }
            }

        // Throw exception if any hooks fail during execution
        if (failures.isNotEmpty()) {
            throw HookExecutionException(
                "Multiple hooks failed during beforeUserCreate execution",
                failures
            )
        }

        return current
    }

    suspend fun executeBeforeUserUpdate(
        userId: UUID,
        email: String?,
        phone: String?
    ): UserUpdateData {
        var current = UserUpdateData(email, phone)
        val failures = mutableListOf<HookFailure>()

        registry.getAllOfType(UserLifecycleHooks::class)
            .sortedBy { it.priority }
            .forEach { hook ->
                when (failureStrategy) {
                    HookFailureStrategy.FAIL_FAST -> {
                        current = hook.beforeUserUpdate(userId, current.email, current.phone)
                    }
                    HookFailureStrategy.COLLECT_ERRORS -> {
                        try {
                            current = hook.beforeUserUpdate(userId, current.email, current.phone)
                        } catch (e: Throwable) {
                            failures.add(HookFailure(hook::class.simpleName ?: "Unknown", e))
                        }
                    }
                    HookFailureStrategy.SKIP_FAILED -> {
                        try {
                            current = hook.beforeUserUpdate(userId, current.email, current.phone)
                        } catch (e: Throwable) {
                            logger.warn("Hook ${hook::class.simpleName} failed in beforeUserUpdate", e)
                        }
                    }
                }
            }

        if (failures.isNotEmpty()) {
            throw HookExecutionException(
                "Multiple hooks failed during beforeUserUpdate execution",
                failures
            )
        }

        return current
    }

    suspend fun executeBeforeProfileUpdate(
        userId: UUID,
        firstName: String?,
        lastName: String?,
        address: String?,
        profilePicture: String?
    ): UserProfileUpdateData {
        var current = UserProfileUpdateData(firstName, lastName, address, profilePicture)
        val failures = mutableListOf<HookFailure>()

        registry.getAllOfType(UserLifecycleHooks::class)
            .sortedBy { it.priority }
            .forEach { hook ->
                when (failureStrategy) {
                    HookFailureStrategy.FAIL_FAST -> {
                        current = hook.beforeProfileUpdate(
                            userId,
                            current.firstName,
                            current.lastName,
                            current.address,
                            current.profilePicture
                        )
                    }
                    HookFailureStrategy.COLLECT_ERRORS -> {
                        try {
                            current = hook.beforeProfileUpdate(
                                userId,
                                current.firstName,
                                current.lastName,
                                current.address,
                                current.profilePicture
                            )
                        } catch (e: Throwable) {
                            failures.add(HookFailure(hook::class.simpleName ?: "Unknown", e))
                        }
                    }
                    HookFailureStrategy.SKIP_FAILED -> {
                        try {
                            current = hook.beforeProfileUpdate(
                                userId,
                                current.firstName,
                                current.lastName,
                                current.address,
                                current.profilePicture
                            )
                        } catch (e: Throwable) {
                            logger.warn("Hook ${hook::class.simpleName} failed in beforeProfileUpdate", e)
                        }
                    }
                }
            }

        if (failures.isNotEmpty()) {
            throw HookExecutionException(
                "Multiple hooks failed during beforeProfileUpdate execution",
                failures
            )
        }

        return current
    }

    suspend fun executeBeforeCustomAttributesUpdate(
        userId: UUID,
        customAttributes: Map<String, String>
    ): Map<String, String> {
        var current = customAttributes
        val failures = mutableListOf<HookFailure>()

        registry.getAllOfType(UserLifecycleHooks::class)
            .sortedBy { it.priority }
            .forEach { hook ->
                when (failureStrategy) {
                    HookFailureStrategy.FAIL_FAST -> {
                        current = hook.beforeCustomAttributesUpdate(userId, current)
                    }
                    HookFailureStrategy.COLLECT_ERRORS -> {
                        try {
                            current = hook.beforeCustomAttributesUpdate(userId, current)
                        } catch (e: Throwable) {
                            failures.add(HookFailure(hook::class.simpleName ?: "Unknown", e))
                        }
                    }
                    HookFailureStrategy.SKIP_FAILED -> {
                        try {
                            current = hook.beforeCustomAttributesUpdate(userId, current)
                        } catch (e: Throwable) {
                            logger.warn("Hook ${hook::class.simpleName} failed in beforeCustomAttributesUpdate", e)
                        }
                    }
                }
            }

        if (failures.isNotEmpty()) {
            throw HookExecutionException(
                "Multiple hooks failed during beforeCustomAttributesUpdate execution",
                failures
            )
        }

        return current
    }

    suspend fun executeBeforeLogin(identifier: String): String {
        var current = identifier
        val failures = mutableListOf<HookFailure>()

        registry.getAllOfType(UserLifecycleHooks::class)
            .sortedBy { it.priority }
            .forEach { hook ->
                when (failureStrategy) {
                    HookFailureStrategy.FAIL_FAST -> {
                        current = hook.beforeLogin(current)
                    }
                    HookFailureStrategy.COLLECT_ERRORS -> {
                        try {
                            current = hook.beforeLogin(current)
                        } catch (e: Throwable) {
                            failures.add(HookFailure(hook::class.simpleName ?: "Unknown", e))
                        }
                    }
                    HookFailureStrategy.SKIP_FAILED -> {
                        try {
                            current = hook.beforeLogin(current)
                        } catch (e: Throwable) {
                            logger.warn("Hook ${hook::class.simpleName} failed in beforeLogin", e)
                        }
                    }
                }
            }

        if (failures.isNotEmpty()) {
            throw HookExecutionException(
                "Multiple hooks failed during beforeLogin execution",
                failures
            )
        }

        return current
    }

    suspend fun executeAfterLoginFailure(identifier: String) {
        val failures = mutableListOf<HookFailure>()

        registry.getAllOfType(UserLifecycleHooks::class)
            .sortedBy { it.priority }
            .forEach { hook ->
                when (failureStrategy) {
                    HookFailureStrategy.FAIL_FAST -> {
                        hook.afterLoginFailure(identifier)
                    }
                    HookFailureStrategy.COLLECT_ERRORS -> {
                        try {
                            hook.afterLoginFailure(identifier)
                        } catch (e: Throwable) {
                            failures.add(HookFailure(hook::class.simpleName ?: "Unknown", e))
                        }
                    }
                    HookFailureStrategy.SKIP_FAILED -> {
                        try {
                            hook.afterLoginFailure(identifier)
                        } catch (e: Throwable) {
                            logger.warn("Hook ${hook::class.simpleName} failed in afterLoginFailure", e)
                        }
                    }
                }
            }

        if (failures.isNotEmpty()) {
            throw HookExecutionException(
                "Multiple hooks failed during afterLoginFailure execution",
                failures
            )
        }
    }
}
