package com.mustafadakhel.kodex.service

import com.auth0.jwt.JWT
import com.mustafadakhel.kodex.model.*
import com.mustafadakhel.kodex.model.database.FullUserEntity
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.model.database.UserProfileEntity
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.security.AccountLockoutService
import com.mustafadakhel.kodex.security.LockoutResult
import com.mustafadakhel.kodex.throwable.KodexThrowable.*
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.token.TokenPair
import com.mustafadakhel.kodex.util.getCurrentLocalDateTime
import kotlinx.datetime.TimeZone
import java.util.*

internal class KodexRealmService(
    private val userRepository: UserRepository,
    private val tokenManager: TokenManager,
    private val hashingService: HashingService,
    private val timeZone: TimeZone,
    internal val realm: Realm,
    private val accountLockoutService: AccountLockoutService,
) : KodexService {

    override fun getAllUsers(): List<User> {
        return userRepository.getAll().map { userEntity ->
            userEntity.toUser()
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

    override fun updateUserById(
        userId: UUID,
        email: String?,
        phone: String?
    ) {
        val result = userRepository.updateById(
            userId = userId,
            email = email,
            phone = phone,
            currentTime = getCurrentLocalDateTime(timeZone)
        )

        result.successOrThrow()
    }

    override fun updateUserProfileById(
        userId: UUID,
        firstName: String?,
        lastName: String?,
        address: String?,
        profilePicture: String?
    ) {
        val success = userRepository.updateProfileByUserId(
            userId = userId,
            profile = UserProfile(
                firstName = firstName,
                lastName = lastName,
                address = address,
                profilePicture = profilePicture,
            )
        )
        if (success.not()) throw UserUpdateFailed(userId)
    }

    override fun getUserProfileOrNull(userId: UUID): UserProfile? {
        val profile = userRepository.findProfileByUserId(userId) ?: return null
        return profile.toUserProfile()
    }

    override fun getSeededRoles(): List<String> {
        return userRepository.getAllRoles().map { it.name }
    }

    override suspend fun tokenByEmail(email: String, password: String): TokenPair {
        checkLockout(email)

        val user = userRepository.findByEmail(email)
        if (user == null) {
            accountLockoutService.recordFailedAttempt(
                identifier = email,
                ipAddress = "unknown",
                userAgent = null,
                reason = "User not found"
            )
            throw Authorization.InvalidCredentials
        }

        if (!authenticateInternal(password, user.id)) {
            accountLockoutService.recordFailedAttempt(
                identifier = email,
                ipAddress = "unknown",
                userAgent = null,
                reason = "Invalid password"
            )
            throw Authorization.InvalidCredentials
        }

        if (!user.isVerified) {
            throw Authorization.UnverifiedAccount
        }

        accountLockoutService.clearFailedAttempts(email)
        return generateTokenInternal(user.id)
    }

    override suspend fun tokenByPhone(
        phone: String,
        password: String
    ): TokenPair {
        checkLockout(phone)

        val user = userRepository.findByPhone(phone)
        if (user == null) {
            accountLockoutService.recordFailedAttempt(
                identifier = phone,
                ipAddress = "unknown",
                userAgent = null,
                reason = "User not found"
            )
            throw Authorization.InvalidCredentials
        }

        if (!authenticateInternal(password, user.id)) {
            accountLockoutService.recordFailedAttempt(
                identifier = phone,
                ipAddress = "unknown",
                userAgent = null,
                reason = "Invalid password"
            )
            throw Authorization.InvalidCredentials
        }

        if (!user.isVerified) {
            throw Authorization.UnverifiedAccount
        }

        accountLockoutService.clearFailedAttempts(phone)
        return generateTokenInternal(user.id)
    }

    private fun checkLockout(identifier: String) {
        val lockoutResult = accountLockoutService.checkLockout(identifier, timeZone)
        if (lockoutResult is LockoutResult.Locked) {
            throw Authorization.AccountLocked(
                lockedUntil = lockoutResult.lockedUntil,
                reason = lockoutResult.reason
            )
        }
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

    override suspend fun refresh(
        userId: UUID,
        refreshToken: String
    ): TokenPair {
        return tokenManager.refreshTokens(userId, refreshToken)
    }

    override fun createUser(
        email: String?,
        phone: String?,
        password: String,
        roleNames: List<String>,
        customAttributes: Map<String, String>?,
        profile: UserProfile?,
    ): User? {
        val result = userRepository.create(
            email = email,
            phone = phone,
            hashedPassword = hashingService.hash(password),
            roleNames = (listOf(realm.owner) + roleNames).distinct(),
            currentTime = getCurrentLocalDateTime(timeZone),
            customAttributes = customAttributes,
            profile = profile,
        )
        return result.userOrThrow().toUser()
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

    override fun replaceAllCustomAttributes(userId: UUID, customAttributes: Map<String, String>) {
        userRepository.replaceAllCustomAttributesByUserId(
            userId = userId,
            customAttributes = customAttributes
        )
    }

    override fun updateCustomAttributes(userId: UUID, customAttributes: Map<String, String>) {
        return userRepository.updateCustomAttributesByUserId(
            userId = userId,
            customAttributes = customAttributes
        ).successOrThrow()
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

    private fun UserProfileEntity.toUserProfile() = UserProfile(
        firstName = firstName,
        lastName = lastName,
        address = address,
        profilePicture = profilePicture,
    )

    private fun FullUserEntity.toFullUser() = FullUser(
        id = id,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isVerified = isVerified,
        email = email,
        phoneNumber = phoneNumber,
        lastLoggedIn = lastLoggedIn,
        status = status,
        roles = roles.map { Role(it.name, it.description) },
        profile = profile?.toUserProfile(),
        customAttributes = customAttributes.orEmpty()
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
