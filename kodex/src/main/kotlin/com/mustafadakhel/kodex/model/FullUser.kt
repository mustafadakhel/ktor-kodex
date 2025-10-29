package com.mustafadakhel.kodex.model

import kotlinx.datetime.LocalDateTime
import java.util.*

public data class FullUser(
    val id: UUID,
    val phoneNumber: String? = null,
    val email: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val isVerified: Boolean,
    val lastLoggedIn: LocalDateTime?,
    val roles: List<Role>,
    val profile: UserProfile?,
    val status: UserStatus,
    val customAttributes: Map<String, String>? = null,
)
