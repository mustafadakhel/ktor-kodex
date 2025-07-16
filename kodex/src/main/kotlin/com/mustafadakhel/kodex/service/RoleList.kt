package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.model.Role

public class RoleList private constructor(
    public val roles: List<Role>
) {
    public fun asString(): String = roles.joinToString(separator = ",") { it.name }

    public companion object {
        public fun from(vararg roles: Role): RoleList = RoleList(roles.toList())
    }
}