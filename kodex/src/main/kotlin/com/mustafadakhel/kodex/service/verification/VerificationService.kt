package com.mustafadakhel.kodex.service.verification

import java.util.UUID

/** Handles user verification status. */
public interface VerificationService {
    /** Sets verification status for a user. */
    public fun setVerified(userId: UUID, verified: Boolean)
}
