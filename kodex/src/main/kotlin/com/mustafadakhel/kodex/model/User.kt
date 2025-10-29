package com.mustafadakhel.kodex.model

import kotlinx.datetime.LocalDateTime
import java.util.*

public data class User(
    val id: UUID,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val isVerified: Boolean,
    val phoneNumber: String? = null,
    val email: String? = null,
    val lastLoggedIn: LocalDateTime? = null,
    val status: UserStatus,
)