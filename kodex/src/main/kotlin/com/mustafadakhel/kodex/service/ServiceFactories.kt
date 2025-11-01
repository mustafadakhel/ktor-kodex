package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.auth.AuthService
import com.mustafadakhel.kodex.service.auth.DefaultAuthService
import com.mustafadakhel.kodex.service.token.DefaultTokenService
import com.mustafadakhel.kodex.service.token.TokenService
import com.mustafadakhel.kodex.service.user.DefaultUserService
import com.mustafadakhel.kodex.service.user.UserService
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import kotlinx.datetime.TimeZone

/**
 * Groups common service dependencies to reduce parameter repetition.
 */
internal data class ServiceContext(
    val realm: Realm,
    val eventBus: EventBus,
    val timeZone: TimeZone,
    val userRepository: UserRepository,
    val passwordHasher: HashingService,
    val hookExecutor: HookExecutor
)

/**
 * Factory functions for creating service instances.
 *
 * Encapsulates dependency injection logic for the three core services:
 * UserService, AuthService, and TokenService.
 */

internal fun userService(
    context: ServiceContext,
    updateCommandProcessor: UpdateCommandProcessor
): UserService {
    return DefaultUserService(
        context.userRepository,
        context.passwordHasher,
        context.hookExecutor,
        context.eventBus,
        updateCommandProcessor,
        context.timeZone,
        context.realm
    )
}

internal fun authService(
    context: ServiceContext,
    tokenService: TokenService
): AuthService {
    return DefaultAuthService(
        context.userRepository,
        context.passwordHasher,
        tokenService,
        context.hookExecutor,
        context.eventBus,
        context.timeZone,
        context.realm
    )
}

internal fun tokenService(
    tokenManager: TokenManager,
    eventBus: EventBus,
    realm: Realm
): TokenService {
    return DefaultTokenService(tokenManager, eventBus, realm)
}
