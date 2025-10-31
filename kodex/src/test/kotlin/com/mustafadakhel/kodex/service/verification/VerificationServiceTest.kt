package com.mustafadakhel.kodex.service.verification

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.service.user.DefaultUserService
import com.mustafadakhel.kodex.service.user.UserService
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.TimeZone
import java.util.UUID

class VerificationServiceTest : FunSpec({
    lateinit var userRepository: UserRepository
    lateinit var hashingService: HashingService
    lateinit var hookExecutor: HookExecutor
    lateinit var eventBus: EventBus
    lateinit var updateCommandProcessor: UpdateCommandProcessor
    lateinit var timeZone: TimeZone
    lateinit var realm: Realm
    lateinit var verificationService: UserService

    beforeEach {
        userRepository = mockk()
        hashingService = mockk(relaxed = true)
        hookExecutor = mockk(relaxed = true)
        eventBus = mockk(relaxed = true)
        updateCommandProcessor = mockk(relaxed = true)
        timeZone = TimeZone.UTC
        realm = mockk()
        every { realm.owner } returns "test-realm"

        verificationService = DefaultUserService(
            userRepository,
            hashingService,
            hookExecutor,
            eventBus,
            updateCommandProcessor,
            timeZone,
            realm
        )
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
