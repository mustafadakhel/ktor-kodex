package com.mustafadakhel.kodex.service

import com.auth0.jwt.JWT
import com.mustafadakhel.kodex.audit.*
import com.mustafadakhel.kodex.extension.ExtensionRegistry
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.*
import com.mustafadakhel.kodex.model.database.FullUserEntity
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.model.database.UserProfileEntity
import com.mustafadakhel.kodex.model.database.toFullUser
import com.mustafadakhel.kodex.model.database.toUserProfile
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.throwable.KodexThrowable.*
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.token.TokenPair
import com.mustafadakhel.kodex.update.ChangeTracker
import com.mustafadakhel.kodex.update.UpdateCommand
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import com.mustafadakhel.kodex.update.UpdateResult
import com.mustafadakhel.kodex.util.getCurrentLocalDateTime
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import java.util.*

internal class KodexRealmService(
    private val userRepository: UserRepository,
    private val tokenManager: TokenManager,
    private val hashingService: HashingService,
    private val timeZone: TimeZone,
    internal val realm: Realm,
    private val extensions: ExtensionRegistry,
) : KodexService {

    private val hookExecutor = HookExecutor(extensions)
    private val changeTracker = ChangeTracker()
    private val updateCommandProcessor = UpdateCommandProcessor(
        userRepository = userRepository,
        hookExecutor = hookExecutor,
        changeTracker = changeTracker,
        timeZone = timeZone
    )

    // Dummy hash for constant-time verification when user doesn't exist
    // Prevents timing attacks that reveal whether a user exists
    private val dummyHash = hashingService.hash("dummy-password-for-timing-attack-prevention")

    override fun getAllUsers(): List<User> {
        return userRepository.getAll().map { userEntity ->
            userEntity.toUser()
        }
    }

    override fun getAllFullUsers(): List<FullUser> {
        return userRepository.getAllFull().map { fullUserEntity ->
            fullUserEntity.toFullUser()
        }
    }

    override fun getUser(userId: UUID): User {
        return userRepository.findById(userId)?.toUser()
            ?: throw UserNotFound("User with id $userId not found")
    }

    override fun getUserOrNull(userId: UUID): User? {
        val user = userRepository.findById(userId)
        return user?.toUser()
    }

    override fun getUserProfile(userId: UUID): UserProfile {
        return getUserProfileOrNull(userId)
            ?: throw ProfileNotFound(userId)
    }

    override fun getUserByEmail(email: String): User {
        return userRepository.findByEmail(email)?.toUser()
            ?: throw UserNotFound("User with email $email not found")
    }

    override fun getUserByPhone(phone: String): User {
        return userRepository.findByPhone(phone)?.toUser()
            ?: throw UserNotFound("User with phone number $phone not found")
    }

    @Deprecated("Use updateUser() with UpdateUserFields", level = DeprecationLevel.WARNING)
    override suspend fun updateUserById(
        userId: UUID,
        email: String?,
        phone: String?
    ) {
        val timestamp = Clock.System.now()

        // Execute beforeUserUpdate hooks (validation, transformation)
        val transformed = hookExecutor.executeBeforeUserUpdate(userId, email, phone)

        val result = userRepository.updateById(
            userId = userId,
            email = transformed.email,
            phone = transformed.phone,
            isVerified = null,
            status = null,
            currentTime = getCurrentLocalDateTime(timeZone)
        )

        result.successOrThrow()

        // Audit user update
        hookExecutor.executeAuditEvent(
            AuditEvent(
                eventType = "USER_UPDATED",
                timestamp = timestamp,
                actorId = userId,
                actorType = ActorType.USER,
                targetId = userId,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "email" to (transformed.email ?: ""),
                    "phone" to (transformed.phone ?: "")
                ),
                realmId = realm.owner
            )
        )
    }

    @Deprecated("Use updateUser() with UpdateProfileFields", level = DeprecationLevel.WARNING)
    override suspend fun updateUserProfileById(
        userId: UUID,
        firstName: String?,
        lastName: String?,
        address: String?,
        profilePicture: String?
    ) {
        val timestamp = Clock.System.now()

        // Execute beforeProfileUpdate hooks (validation, transformation)
        val transformed = hookExecutor.executeBeforeProfileUpdate(
            userId = userId,
            firstName = firstName,
            lastName = lastName,
            address = address,
            profilePicture = profilePicture
        )

        val result = userRepository.updateProfileByUserId(
            userId = userId,
            profile = UserProfile(
                firstName = transformed.firstName,
                lastName = transformed.lastName,
                address = transformed.address,
                profilePicture = transformed.profilePicture,
            )
        )

        result.successOrThrow()

        // Audit profile update
        hookExecutor.executeAuditEvent(
            AuditEvent(
                eventType = "USER_PROFILE_UPDATED",
                timestamp = timestamp,
                actorId = userId,
                actorType = ActorType.USER,
                targetId = userId,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "firstName" to (transformed.firstName ?: ""),
                    "lastName" to (transformed.lastName ?: ""),
                    "address" to (transformed.address ?: ""),
                    "profilePicture" to (transformed.profilePicture ?: "")
                ),
                realmId = realm.owner
            )
        )
    }

    override suspend fun updateUser(command: UpdateCommand): UpdateResult {
        val result = updateCommandProcessor.execute(command)

        // Audit the update attempt
        when (result) {
            is UpdateResult.Success -> {
                if (result.hasChanges()) {
                    // Build detailed change metadata
                    val changeMetadata = buildMap<String, String> {
                        put("changeCount", result.changes.changedFields.size.toString())

                        // Add each field change with old → new values
                        result.changes.changedFields.forEach { (fieldName, change) ->
                            put("$fieldName.old", change.oldValue?.toString() ?: "null")
                            put("$fieldName.new", change.newValue?.toString() ?: "null")
                        }
                    }

                    runCatching {
                        hookExecutor.executeAuditEvent(
                            AuditEvent(
                                eventType = "USER_UPDATED",
                                timestamp = result.changes.timestamp,
                                actorId = command.userId,
                                actorType = ActorType.USER,
                                targetId = command.userId,
                                result = EventResult.SUCCESS,
                                metadata = changeMetadata,
                                realmId = realm.owner
                            )
                        )
                    }
                }
            }
            is UpdateResult.Failure -> {
                // Audit failed update attempts
                val failureMetadata = when (result) {
                    is UpdateResult.Failure.NotFound -> mapOf(
                        "reason" to "User not found",
                        "userId" to result.userId.toString()
                    )
                    is UpdateResult.Failure.ValidationFailed -> mapOf(
                        "reason" to "Validation failed",
                        "errors" to result.errors.joinToString("; ") { "${it.field}: ${it.message}" }
                    )
                    is UpdateResult.Failure.ConstraintViolation -> mapOf(
                        "reason" to "Constraint violation",
                        "field" to result.field,
                        "details" to result.reason
                    )
                    is UpdateResult.Failure.Unknown -> mapOf(
                        "reason" to "Unknown error",
                        "message" to result.message
                    )
                }

                runCatching {
                    hookExecutor.executeAuditEvent(
                        AuditEvent(
                            eventType = "USER_UPDATE_FAILED",
                            timestamp = Clock.System.now(),
                            actorId = command.userId,
                            actorType = ActorType.USER,
                            targetId = command.userId,
                            result = EventResult.FAILURE,
                            metadata = failureMetadata,
                            realmId = realm.owner
                        )
                    )
                }
            }
        }

        return result
    }

    override fun getUserProfileOrNull(userId: UUID): UserProfile? {
        val profile = userRepository.findProfileByUserId(userId) ?: return null
        return profile.toUserProfile()
    }

    override fun getSeededRoles(): List<String> {
        return userRepository.getAllRoles().map { it.name }
    }

    override suspend fun updateUserRoles(userId: UUID, roleNames: List<String>) {
        val timestamp = Clock.System.now()

        // Verify user exists
        userRepository.findById(userId) ?: throw UserNotFound("User with id $userId not found")

        // Get current roles for audit
        val currentRoles = userRepository.findRoles(userId).map { it.name }

        // Update roles
        val result = userRepository.updateRolesForUser(userId, roleNames)

        when (result) {
            is UserRepository.UpdateRolesResult.Success -> {
                // Audit role update
                hookExecutor.executeAuditEvent(
                    AuditEvent(
                        eventType = "USER_ROLES_UPDATED",
                        timestamp = timestamp,
                        actorType = ActorType.ADMIN,
                        targetId = userId,
                        result = EventResult.SUCCESS,
                        metadata = mapOf(
                            "previousRoles" to currentRoles.joinToString(","),
                            "newRoles" to roleNames.joinToString(","),
                            "addedRoles" to (roleNames - currentRoles.toSet()).joinToString(","),
                            "removedRoles" to (currentRoles - roleNames.toSet()).joinToString(",")
                        ),
                        realmId = realm.owner
                    )
                )
            }
            is UserRepository.UpdateRolesResult.InvalidRole -> {
                // Audit failed role update
                hookExecutor.executeAuditEvent(
                    AuditEvent(
                        eventType = "USER_ROLES_UPDATE_FAILED",
                        timestamp = timestamp,
                        actorType = ActorType.ADMIN,
                        targetId = userId,
                        result = EventResult.FAILURE,
                        metadata = mapOf(
                            "reason" to "Invalid role",
                            "roleName" to result.roleName
                        ),
                        realmId = realm.owner
                    )
                )
                throw RoleNotFound(result.roleName)
            }
        }
    }

    override suspend fun tokenByEmail(email: String, password: String): TokenPair =
        authenticateAndGenerateToken(
            identifier = email,
            password = password,
            identifierType = "email",
            userFetcher = { userRepository.findByEmail(it) }
        )

    override suspend fun tokenByPhone(phone: String, password: String): TokenPair =
        authenticateAndGenerateToken(
            identifier = phone,
            password = password,
            identifierType = "phone",
            userFetcher = { userRepository.findByPhone(it) }
        )

    /**
     * Common authentication logic for both email and phone-based login.
     * Prevents information leakage by using identical error paths for security.
     */
    private suspend fun authenticateAndGenerateToken(
        identifier: String,
        password: String,
        identifierType: String,
        userFetcher: suspend (String) -> UserEntity?
    ): TokenPair {
        // Capture timestamp once for consistency across all audit events
        val timestamp = Clock.System.now()

        // Execute beforeLogin hooks (e.g., lockout check)
        hookExecutor.executeBeforeLogin(identifier)

        val user = userFetcher(identifier)

        // Security: ALWAYS verify password to prevent timing attacks
        // When user doesn't exist, verify against dummy hash to maintain constant timing
        val authSuccess = if (user != null) {
            authenticateInternal(password, user.id)
        } else {
            // Perform dummy verification to prevent timing-based user enumeration
            hashingService.verify(password, dummyHash)
            false
        }

        if (!authSuccess) {
            // Execute afterLoginFailure hooks
            hookExecutor.executeAfterLoginFailure(identifier)

            // Audit failed login (detailed reason only in server logs)
            val actualReason = when {
                user == null -> "User not found"
                else -> "Invalid password"
            }

            hookExecutor.executeAuditEvent(
                AuditEvent(
                    eventType = "LOGIN_FAILED",
                    timestamp = timestamp,
                    actorType = if (user == null) ActorType.ANONYMOUS else ActorType.USER,
                    targetId = user?.id,
                    result = EventResult.FAILURE,
                    metadata = mapOf(
                        "identifier" to identifier,
                        "reason" to actualReason, // Detailed reason only in audit logs
                        "method" to identifierType
                    ),
                    realmId = realm.owner
                )
            )

            // Always throw same exception regardless of reason
            throw Authorization.InvalidCredentials
        }

        // Check verification status
        if (!user!!.isVerified) {
            throw Authorization.UnverifiedAccount
        }

        // Update last login time
        userRepository.updateLastLogin(user.id, getCurrentLocalDateTime(timeZone))

        // Audit successful login
        hookExecutor.executeAuditEvent(
            AuditEvent(
                eventType = "LOGIN_SUCCESS",
                timestamp = timestamp,
                actorId = user.id,
                actorType = ActorType.USER,
                targetId = user.id,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "identifier" to identifier,
                    "method" to identifierType
                ),
                realmId = realm.owner
            )
        )

        return generateTokenInternal(user.id)
    }

    private fun authenticateInternal(password: String, userId: UUID): Boolean {
        val storedPassword = userRepository.getHashedPassword(userId) ?: return false
        return hashingService.verify(password, storedPassword)
    }

    private suspend fun generateTokenInternal(userId: UUID): TokenPair {
        val token = tokenManager.issueNewTokens(userId)
        return token
    }

    override fun revokeTokens(userId: UUID) {
        tokenManager.revokeTokensForUser(userId)
    }

    override fun revokeToken(token: String, delete: Boolean) {
        tokenManager.revokeToken(token)
    }

    override fun setVerified(userId: UUID, verified: Boolean) {
        userRepository.setVerified(userId, verified)
    }

    override suspend fun changePassword(userId: UUID, oldPassword: String, newPassword: String) {
        val timestamp = Clock.System.now()

        // Verify user exists
        val user = userRepository.findById(userId) ?: throw UserNotFound("User with id $userId not found")

        // Verify old password
        if (!authenticateInternal(oldPassword, userId)) {
            // Audit failed password change
            hookExecutor.executeAuditEvent(
                AuditEvent(
                    eventType = "PASSWORD_CHANGE_FAILED",
                    timestamp = timestamp,
                    actorId = userId,
                    actorType = ActorType.USER,
                    targetId = userId,
                    result = EventResult.FAILURE,
                    metadata = mapOf("reason" to "Invalid old password"),
                    realmId = realm.owner
                )
            )
            throw Authorization.InvalidCredentials
        }

        // Hash new password
        val hashedPassword = hashingService.hash(newPassword)

        // Update password
        val success = userRepository.updatePassword(userId, hashedPassword)
        if (!success) {
            throw UserNotFound("User with id $userId not found")
        }

        // Audit successful password change
        hookExecutor.executeAuditEvent(
            AuditEvent(
                eventType = "PASSWORD_CHANGED",
                timestamp = timestamp,
                actorId = userId,
                actorType = ActorType.USER,
                targetId = userId,
                result = EventResult.SUCCESS,
                metadata = emptyMap(),
                realmId = realm.owner
            )
        )
    }

    override suspend fun resetPassword(userId: UUID, newPassword: String) {
        val timestamp = Clock.System.now()

        // Verify user exists
        val user = userRepository.findById(userId) ?: throw UserNotFound("User with id $userId not found")

        // Hash new password
        val hashedPassword = hashingService.hash(newPassword)

        // Update password
        val success = userRepository.updatePassword(userId, hashedPassword)
        if (!success) {
            throw UserNotFound("User with id $userId not found")
        }

        // Audit password reset
        hookExecutor.executeAuditEvent(
            AuditEvent(
                eventType = "PASSWORD_RESET",
                timestamp = timestamp,
                actorType = ActorType.ADMIN,
                targetId = userId,
                result = EventResult.SUCCESS,
                metadata = emptyMap(),
                realmId = realm.owner
            )
        )
    }

    override suspend fun refresh(
        userId: UUID,
        refreshToken: String
    ): TokenPair {
        return tokenManager.refreshTokens(userId, refreshToken)
    }

    override suspend fun createUser(
        email: String?,
        phone: String?,
        password: String,
        roleNames: List<String>,
        customAttributes: Map<String, String>?,
        profile: UserProfile?,
    ): User? {
        // Execute beforeUserCreate hooks (validation, transformation)
        val transformed = hookExecutor.executeBeforeUserCreate(
            email, phone, password, customAttributes, profile
        )

        val result = userRepository.create(
            email = transformed.email,
            phone = transformed.phone,
            hashedPassword = hashingService.hash(password),
            roleNames = (listOf(realm.owner) + roleNames).distinct(),
            currentTime = getCurrentLocalDateTime(timeZone),
            customAttributes = transformed.customAttributes,
            profile = transformed.profile,
        )
        val user = result.userOrThrow().toUser()

        // Audit user creation
        kotlinx.coroutines.runBlocking {
            hookExecutor.executeAuditEvent(
                AuditEvent(
                    eventType = "USER_CREATED",
                    timestamp = Clock.System.now(),
                    actorType = ActorType.SYSTEM,
                    targetId = user.id,
                    result = EventResult.SUCCESS,
                    metadata = mapOf(
                        "email" to (email ?: ""),
                        "phone" to (phone ?: "")
                    ),
                    realmId = realm.owner
                )
            )
        }

        return user
    }

    override fun getFullUser(userId: UUID): FullUser {
        return getFullUserOrNull(userId) ?: throw UserNotFound("User with id $userId not found")
    }

    override fun getFullUserOrNull(userId: UUID): FullUser? {
        val user = userRepository.findFullById(userId)

        return user?.toFullUser()
    }

    override fun getCustomAttributes(userId: UUID): Map<String, String> {
        return userRepository.findCustomAttributesByUserId(userId)
    }

    @Deprecated("Use updateUser() with UpdateAttributes", level = DeprecationLevel.WARNING)
    override suspend fun replaceAllCustomAttributes(userId: UUID, customAttributes: Map<String, String>) {
        val timestamp = Clock.System.now()

        // Execute beforeCustomAttributesUpdate hooks (validation, sanitization)
        val sanitized = hookExecutor.executeBeforeCustomAttributesUpdate(userId, customAttributes)

        val result = userRepository.replaceAllCustomAttributesByUserId(
            userId = userId,
            customAttributes = sanitized
        )

        result.successOrThrow()

        // Audit custom attributes replacement
        hookExecutor.executeAuditEvent(
            AuditEvent(
                eventType = "CUSTOM_ATTRIBUTES_REPLACED",
                timestamp = timestamp,
                actorId = userId,
                actorType = ActorType.USER,
                targetId = userId,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "attributeCount" to sanitized.size.toString(),
                    "keys" to sanitized.keys.joinToString(",")
                ),
                realmId = realm.owner
            )
        )
    }

    @Deprecated("Use updateUser() with UpdateAttributes", level = DeprecationLevel.WARNING)
    override suspend fun updateCustomAttributes(userId: UUID, customAttributes: Map<String, String>) {
        val timestamp = Clock.System.now()

        // Execute beforeCustomAttributesUpdate hooks (validation, sanitization)
        val sanitized = hookExecutor.executeBeforeCustomAttributesUpdate(userId, customAttributes)

        userRepository.updateCustomAttributesByUserId(
            userId = userId,
            customAttributes = sanitized
        ).successOrThrow()

        // Audit custom attributes update
        hookExecutor.executeAuditEvent(
            AuditEvent(
                eventType = "CUSTOM_ATTRIBUTES_UPDATED",
                timestamp = timestamp,
                actorId = userId,
                actorType = ActorType.USER,
                targetId = userId,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "attributeCount" to sanitized.size.toString(),
                    "keys" to sanitized.keys.joinToString(",")
                ),
                realmId = realm.owner
            )
        )
    }

    override fun verifyAccessToken(token: String): KodexPrincipal? {
        val jwt = JWT.decode(token)
        return runCatching {
            tokenManager.verifyToken(jwt, TokenType.AccessToken)
        }.getOrNull()
    }

    private fun UserEntity.toUser() = User(
        id = id,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isVerified = isVerified,
        email = email,
        phoneNumber = phoneNumber,
        lastLoggedIn = lastLoggedIn,
        status = status
    )
}

private fun UserRepository.CreateUserResult.userOrThrow() = when (this) {
    is UserRepository.CreateUserResult.EmailAlreadyExists ->
        throw EmailAlreadyExists()

    is UserRepository.CreateUserResult.InvalidRole -> throw RoleNotFound(roleName)
    is UserRepository.CreateUserResult.Success -> user
    is UserRepository.CreateUserResult.PhoneAlreadyExists ->
        throw PhoneAlreadyExists()
}

private fun UserRepository.UpdateUserResult.successOrThrow() = when (this) {
    UserRepository.UpdateUserResult.EmailAlreadyExists ->
        throw EmailAlreadyExists()

    is UserRepository.UpdateUserResult.InvalidRole ->
        throw RoleNotFound(roleName)

    UserRepository.UpdateUserResult.NotFound ->
        throw UserNotFound()

    UserRepository.UpdateUserResult.PhoneAlreadyExists ->
        throw PhoneAlreadyExists()

    UserRepository.UpdateUserResult.Success -> Unit
}

private fun UserRepository.UpdateProfileResult.successOrThrow() = when (this) {
    UserRepository.UpdateProfileResult.NotFound ->
        throw UserNotFound()

    is UserRepository.UpdateProfileResult.Success -> Unit
}
