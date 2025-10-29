package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.Role
import io.ktor.utils.io.*

/**
 * Scope used to provide roles for the kodex realm.
 */
public interface RolesConfigScope {
    /** Adds a role with the given [name] and optional [description]. */
    public fun role(name: String, description: String? = null)

    /** Adds multiple roles from a list. */
    public fun roles(list: List<Role>)
}

/** Internal builder implementing [RolesConfigScope]. */
@KtorDsl
internal class RolesConfig(
    realm: Realm,
) : RolesConfigScope {

    internal val roles: MutableList<Role> = mutableListOf(
        Role(
            name = realm.owner,
            description = realm.name
        )
    )

    override fun role(name: String, description: String?) {
        roles.add(Role(name = name, description = description))
    }

    override fun roles(list: List<Role>) {
        roles.addAll(list)
    }
}
