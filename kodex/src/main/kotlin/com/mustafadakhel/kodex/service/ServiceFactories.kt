package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.extension.ExtensionRegistry
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.auth.AuthenticationService
import com.mustafadakhel.kodex.service.auth.DefaultAuthenticationService
import com.mustafadakhel.kodex.service.role.DefaultRoleService
import com.mustafadakhel.kodex.service.role.RoleService
import com.mustafadakhel.kodex.service.token.DefaultTokenService
import com.mustafadakhel.kodex.service.token.TokenService
import com.mustafadakhel.kodex.service.user.DefaultUserCommandService
import com.mustafadakhel.kodex.service.user.DefaultUserQueryService
import com.mustafadakhel.kodex.service.user.UserCommandService
import com.mustafadakhel.kodex.service.user.UserQueryService
import com.mustafadakhel.kodex.service.verification.DefaultVerificationService
import com.mustafadakhel.kodex.service.verification.VerificationService
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import kotlinx.datetime.TimeZone

/**
 * Factory functions for creating service instances.
 *
 * These functions encapsulate the dependency injection logic for each service,
 * making it easier to instantiate services with the correct dependencies.
 */

internal fun userQueryService(
    userRepository: UserRepository
): UserQueryService {
    return DefaultUserQueryService(userRepository)
}

internal fun userCommandService(
    userRepository: UserRepository,
    hashingService: HashingService,
    hookExecutor: HookExecutor,
    eventBus: EventBus,
    updateCommandProcessor: UpdateCommandProcessor,
    timeZone: TimeZone,
    realm: Realm
): UserCommandService {
    return DefaultUserCommandService(
        userRepository,
        hashingService,
        hookExecutor,
        eventBus,
        updateCommandProcessor,
        timeZone,
        realm
    )
}

internal fun roleService(
    userRepository: UserRepository,
    eventBus: EventBus,
    realm: Realm
): RoleService {
    return DefaultRoleService(userRepository, eventBus, realm)
}

internal fun verificationService(
    userRepository: UserRepository
): VerificationService {
    return DefaultVerificationService(userRepository)
}

internal fun authenticationService(
    userRepository: UserRepository,
    hashingService: HashingService,
    tokenService: TokenService,
    hookExecutor: HookExecutor,
    eventBus: EventBus,
    timeZone: TimeZone,
    realm: Realm
): AuthenticationService {
    return DefaultAuthenticationService(
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
    tokenManager: TokenManager
): TokenService {
    return DefaultTokenService(tokenManager)
}
