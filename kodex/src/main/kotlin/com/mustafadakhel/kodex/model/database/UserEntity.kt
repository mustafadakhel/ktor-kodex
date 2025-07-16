package com.mustafadakhel.kodex.model.database

import com.mustafadakhel.kodex.model.UserStatus
import kotlinx.datetime.LocalDateTime
import java.util.*

internal data class UserEntity(
    val id: UUID,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val isVerified: Boolean,
    val phoneNumber: String? = null,
    val email: String? = null,
    val lastLoggedIn: LocalDateTime? = null,
    val status: UserStatus,
)
