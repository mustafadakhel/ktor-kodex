package com.mustafadakhel.kodex.service.verification

import com.mustafadakhel.kodex.repository.UserRepository
import java.util.UUID

/**
 * Default implementation of VerificationService.
 *
 * This implementation delegates directly to the UserRepository for
 * verification status updates.
 */
internal class DefaultVerificationService(
    private val userRepository: UserRepository
) : VerificationService {

    override fun setVerified(userId: UUID, verified: Boolean) {
        userRepository.setVerified(userId, verified)
    }
}
