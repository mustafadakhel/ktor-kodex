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
 * Factory functions for creating service instances.
 *
 * Encapsulates dependency injection logic for the three core services:
 * UserService, AuthService, and TokenService.
 */

internal fun userService(
    userRepository: UserRepository,
    hashingService: HashingService,
    hookExecutor: HookExecutor,
    eventBus: EventBus,
    updateCommandProcessor: UpdateCommandProcessor,
    timeZone: TimeZone,
    realm: Realm
): UserService {
    return DefaultUserService(
        userRepository,
        hashingService,
        hookExecutor,
        eventBus,
        updateCommandProcessor,
        timeZone,
        realm
    )
}

internal fun authService(
    userRepository: UserRepository,
    hashingService: HashingService,
    tokenService: TokenService,
    hookExecutor: HookExecutor,
    eventBus: EventBus,
    timeZone: TimeZone,
    realm: Realm
): AuthService {
    return DefaultAuthService(
        userRepository,
        hashingService,
        tokenService,
        hookExecutor,
        eventBus,
        timeZone,
        realm
    )
}

internal fun tokenService(
    tokenManager: TokenManager,
    eventBus: EventBus,
    realm: Realm
): TokenService {
    return DefaultTokenService(tokenManager, eventBus, realm)
}
