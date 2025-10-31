package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.service.auth.AuthService
import com.mustafadakhel.kodex.service.token.TokenService
import com.mustafadakhel.kodex.service.user.UserService

/**
 * Service container for a specific realm.
 *
 * Provides access to three domain-focused services:
 * - [users]: User operations (CRUD, profile, roles, verification, attributes)
 * - [auth]: Authentication and password management
 * - [tokens]: Token lifecycle management (issue, refresh, revoke, verify)
 *
 * @property realm The realm these services belong to
 */
public class KodexRealmServices internal constructor(
    public val realm: Realm,
    public val users: UserService,
    public val auth: AuthService,
    public val tokens: TokenService
)
