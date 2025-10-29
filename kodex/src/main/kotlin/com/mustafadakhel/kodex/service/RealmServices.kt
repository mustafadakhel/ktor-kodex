package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.service.auth.AuthenticationService
import com.mustafadakhel.kodex.service.role.RoleService
import com.mustafadakhel.kodex.service.token.TokenService
import com.mustafadakhel.kodex.service.user.UserCommandService
import com.mustafadakhel.kodex.service.user.UserQueryService
import com.mustafadakhel.kodex.service.verification.VerificationService

/**
 * Container for all services associated with a specific realm.
 *
 * This class aggregates the 6 specialized services that replaced the
 * monolithic KodexRealmService. Each service handles a specific domain:
 * - UserQueryService: Read-only user operations (CQRS query side)
 * - UserCommandService: User state mutations (CQRS command side)
 * - RoleService: Role management
 * - VerificationService: User verification status
 * - AuthenticationService: Authentication flows and password management
 * - TokenService: Token lifecycle management
 *
 * @property realm The realm these services belong to
 */
public data class RealmServices(
    val realm: Realm,
    val userQuery: UserQueryService,
    val userCommand: UserCommandService,
    val roles: RoleService,
    val verification: VerificationService,
    val authentication: AuthenticationService,
    val tokens: TokenService
)
