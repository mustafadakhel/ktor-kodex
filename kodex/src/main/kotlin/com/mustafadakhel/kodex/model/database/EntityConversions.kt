package com.mustafadakhel.kodex.model.database

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.UserProfile

internal fun FullUserEntity.toFullUser() = FullUser(
    id = id,
    phoneNumber = phoneNumber,
    email = email,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastLoggedIn = lastLoggedIn,
    roles = roles.map { Role(it.name, it.description) },
    profile = profile?.let {
        UserProfile(
            firstName = it.firstName,
            lastName = it.lastName,
            address = it.address,
            profilePicture = it.profilePicture
        )
    },
    status = status,
    customAttributes = customAttributes
)

internal fun UserProfileEntity.toUserProfile() = UserProfile(
    firstName = firstName,
    lastName = lastName,
    address = address,
    profilePicture = profilePicture
)
