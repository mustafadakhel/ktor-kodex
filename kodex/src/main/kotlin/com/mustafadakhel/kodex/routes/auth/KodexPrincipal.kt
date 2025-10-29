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
    /** Identifier of the authenticated user. */
    public val userId: UUID

    /** Type of token used for authentication. */
    public val type: TokenType

    /** Realm the principal belongs to. */
    public val realm: Realm

    /** Roles granted to the user. */
    public val roles: List<Role>

    /** Raw token string if present. */
    public val token: String?
}

/** Default [KodexPrincipal] implementation. */
public class DefaultKodexPrincipal(
    override val userId: UUID,
    override val type: TokenType,
    override val realm: Realm,
    override val roles: List<Role>,
    override val token: String?
) : KodexPrincipal

/** Returns the [KodexPrincipal] from this call if present. */
public val ApplicationCall.kodex: KodexPrincipal? get() = principal()
