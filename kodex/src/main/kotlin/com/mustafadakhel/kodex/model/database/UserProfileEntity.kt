package com.mustafadakhel.kodex.model.database

import java.util.*

public data class UserProfileEntity(
    val userId: UUID,
    val firstName: String?,
    val lastName: String?,
    val address: String?,
    val profilePicture: String?,
)