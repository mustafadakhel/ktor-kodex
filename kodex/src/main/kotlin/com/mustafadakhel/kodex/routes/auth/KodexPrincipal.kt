package com.mustafadakhel.kodex.routes.auth

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.TokenType
import io.ktor.server.application.*
import io.ktor.server.auth.*
import java.util.*

/**
 * Principal attached to calls authenticated with Kodex.
 */
public interface KodexPrincipal {
    public val userId: UUID

    public val type: TokenType

    public val realm: Realm

    public val roles: List<Role>

    public val token: String?

    /** Token family ID for session tracking. */
    public val tokenFamily: UUID?
}

public class DefaultKodexPrincipal(
    override val userId: UUID,
    override val type: TokenType,
    override val realm: Realm,
    override val roles: List<Role>,
    override val token: String?,
    override val tokenFamily: UUID?
) : KodexPrincipal

public val ApplicationCall.kodex: KodexPrincipal? get() = principal()
