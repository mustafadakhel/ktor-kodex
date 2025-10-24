package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.update.UpdateCommand
import com.mustafadakhel.kodex.update.UpdateResult

/** Handles user write operations. */
public interface UserCommandService {

    /** Creates a new user with the given credentials. */
    public suspend fun createUser(
        email: String?,
        phone: String? = null,
        password: String,
        roleNames: List<String> = emptyList(),
        customAttributes: Map<String, String>? = null,
        profile: UserProfile? = null
    ): User?

    /** Updates a user based on the provided command. */
    public suspend fun updateUser(command: UpdateCommand): UpdateResult
}
