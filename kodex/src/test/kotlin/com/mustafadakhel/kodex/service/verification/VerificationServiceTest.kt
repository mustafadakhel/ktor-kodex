package com.mustafadakhel.kodex.service.verification

import com.mustafadakhel.kodex.repository.UserRepository
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

class VerificationServiceTest : FunSpec({
    lateinit var userRepository: UserRepository
    lateinit var verificationService: VerificationService

    beforeEach {
        userRepository = mockk()
        verificationService = DefaultVerificationService(userRepository)
    }

    test("setVerified should delegate to UserRepository.setVerified with verified=true") {
        val userId = UUID.randomUUID()

        every { userRepository.setVerified(userId, true) } returns true

        verificationService.setVerified(userId, true)

        verify(exactly = 1) { userRepository.setVerified(userId, true) }
    }

    test("setVerified should delegate to UserRepository.setVerified with verified=false") {
        val userId = UUID.randomUUID()

        every { userRepository.setVerified(userId, false) } returns true

        verificationService.setVerified(userId, false)

        verify(exactly = 1) { userRepository.setVerified(userId, false) }
    }

    test("setVerified should handle repository returning false") {
        val userId = UUID.randomUUID()

        every { userRepository.setVerified(userId, true) } returns false

        verificationService.setVerified(userId, true)

        verify(exactly = 1) { userRepository.setVerified(userId, true) }
    }
})
