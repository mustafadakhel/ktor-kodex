package com.mustafadakhel.kodex.service

import com.auth0.jwt.JWT
import com.mustafadakhel.kodex.audit.AuditEvents
import com.mustafadakhel.kodex.event.AuthEvent
import com.mustafadakhel.kodex.event.DefaultEventBus
import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
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
import com.mustafadakhel.kodex.update.FieldUpdate
import com.mustafadakhel.kodex.update.UpdateCommand
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import com.mustafadakhel.kodex.update.UpdateResult
import com.mustafadakhel.kodex.util.getCurrentLocalDateTime
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import java.util.*

@Suppress("DEPRECATION") // Old audit hooks kept for backward compatibility during migration
internal class KodexRealmService(
    private val userRepository: UserRepository,
    private val tokenManager: TokenManager,
    private val hashingService: HashingService,
    private val timeZone: TimeZone,
    internal val realm: Realm,
    private val extensions: ExtensionRegistry,
) : KodexService {

    private val hookExecutor = HookExecutor(extensions)
    private val eventBus: EventBus = DefaultEventBus(extensions)
    private val changeTracker = ChangeTracker()

    init {
        // Automatically register event subscribers from extensions
        registerEventSubscribers()
    }

    /**
     * Registers event subscribers from all extensions that implement EventSubscriberProvider.
     * This allows extensions to automatically subscribe to events without manual wiring.
     */
    private fun registerEventSubscribers() {
        // Get all extensions from the registry
        val allExtensions = extensions.getAllOfType(com.mustafadakhel.kodex.extension.RealmExtension::class)

        // Find extensions that provide event subscribers
        allExtensions.filterIsInstance<EventSubscriberProvider>().forEach { provider ->
            provider.getEventSubscribers().forEach { subscriber ->
                eventBus.subscribe(subscriber)
            }
        }
    }
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
            email = transformed.email?.let { FieldUpdate.SetValue(it) } ?: FieldUpdate.NoChange(),
            phone = transformed.phone?.let { FieldUpdate.SetValue(it) } ?: FieldUpdate.NoChange(),
            isVerified = FieldUpdate.NoChange(),
            status = FieldUpdate.NoChange(),
            currentTime = getCurrentLocalDateTime(timeZone)
        )

        result.successOrThrow()

        // Publish event (new event bus system)
        eventBus.publish(
            UserEvent.Updated(
                eventId = UUID.randomUUID(),
                timestamp = timestamp,
                realmId = realm.owner,
                userId = userId,
                actorId = userId,
                changes = mapOf(
                    "email" to (transformed.email ?: ""),
                    "phone" to (transformed.phone ?: "")
                )
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

        // Publish event (new event bus system)
        eventBus.publish(
            UserEvent.ProfileUpdated(
                eventId = UUID.randomUUID(),
                timestamp = timestamp,
                realmId = realm.owner,
                userId = userId,
                actorId = userId,
                changes = mapOf(
                    "firstName" to (transformed.firstName ?: ""),
                    "lastName" to (transformed.lastName ?: ""),
                    "address" to (transformed.address ?: ""),
                    "profilePicture" to (transformed.profilePicture ?: "")
                )
            )
        )
    }

    override suspend fun updateUser(command: UpdateCommand): UpdateResult {
        val result = updateCommandProcessor.execute(command)

        // Publish events for successful updates
        when (result) {
            is UpdateResult.Success -> {
                if (result.hasChanges()) {
                    // Build change metadata for event
                    val changeMetadata = buildMap<String, String> {
                        result.changes.changedFields.forEach { (fieldName, change) ->
                            put(fieldName, change.newValue?.toString() ?: "")
                        }
                    }

                    runCatching {
                        eventBus.publish(
                            UserEvent.Updated(
                                eventId = UUID.randomUUID(),
                                timestamp = result.changes.timestamp,
                                realmId = realm.owner,
                                userId = command.userId,
                                actorId = command.userId,
                                changes = changeMetadata
                            )
                        )
                    }
                }
            }
            is UpdateResult.Failure -> {
                // TODO: Define failure event types for audit logging
                // Failed operations don't change state, so they may not be domain events
                // Consider adding a separate audit/error tracking mechanism
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
                // Publish event (new event bus system)
                eventBus.publish(
                    UserEvent.RolesUpdated(
                        eventId = UUID.randomUUID(),
                        timestamp = timestamp,
                        realmId = realm.owner,
                        userId = userId,
                        actorType = "ADMIN",
                        previousRoles = currentRoles.toSet(),
                        newRoles = roleNames.toSet()
                    )
                )
            }
            is UserRepository.UpdateRolesResult.InvalidRole -> {
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

            // Publish event (new event bus system)
            eventBus.publish(
                AuthEvent.LoginFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = timestamp,
                    realmId = realm.owner,
                    identifier = identifier,
                    reason = actualReason,
                    method = identifierType,
                    userId = user?.id,
                    actorType = if (user == null) "ANONYMOUS" else "USER"
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

        // Publish event (new event bus system)
        eventBus.publish(
            AuthEvent.LoginSuccess(
                eventId = UUID.randomUUID(),
                timestamp = timestamp,
                realmId = realm.owner,
                userId = user.id,
                identifier = identifier,
                method = identifierType
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
            // Publish event (new event bus system)
            eventBus.publish(
                AuthEvent.PasswordChangeFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = timestamp,
                    realmId = realm.owner,
                    userId = userId,
                    actorId = userId,
                    reason = "Invalid old password"
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

        // Publish event (new event bus system)
        eventBus.publish(
            AuthEvent.PasswordChanged(
                eventId = UUID.randomUUID(),
                timestamp = timestamp,
                realmId = realm.owner,
                userId = userId,
                actorId = userId
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

        // Publish event (new event bus system)
        eventBus.publish(
            AuthEvent.PasswordReset(
                eventId = UUID.randomUUID(),
                timestamp = timestamp,
                realmId = realm.owner,
                userId = userId,
                actorType = "ADMIN"
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

        // Publish event (new event bus system)
        kotlinx.coroutines.runBlocking {
            eventBus.publish(
                UserEvent.Created(
                    eventId = UUID.randomUUID(),
                    timestamp = Clock.System.now(),
                    realmId = realm.owner,
                    userId = user.id,
                    email = email,
                    phone = phone
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

        // Publish event (new event bus system)
        eventBus.publish(
            UserEvent.CustomAttributesReplaced(
                eventId = UUID.randomUUID(),
                timestamp = timestamp,
                realmId = realm.owner,
                userId = userId,
                actorId = userId,
                attributeCount = sanitized.size,
                keys = sanitized.keys
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

        // Publish event (new event bus system)
        eventBus.publish(
            UserEvent.CustomAttributesUpdated(
                eventId = UUID.randomUUID(),
                timestamp = timestamp,
                realmId = realm.owner,
                userId = userId,
                actorId = userId,
                attributeCount = sanitized.size,
                keys = sanitized.keys
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
