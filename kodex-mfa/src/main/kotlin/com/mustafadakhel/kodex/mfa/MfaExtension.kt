package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.mfa.database.MfaBackupCodes
import com.mustafadakhel.kodex.mfa.database.MfaChallenges
import com.mustafadakhel.kodex.mfa.database.MfaMethods
import com.mustafadakhel.kodex.mfa.encryption.SecretEncryption
import com.mustafadakhel.kodex.mfa.session.MfaSessionStore
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.Table
import java.util.UUID
import kotlin.reflect.KClass

public class MfaExtension internal constructor(
    private val config: MfaConfig,
    timeZone: TimeZone,
    hashingService: HashingService,
    secretEncryption: SecretEncryption
) : UserLifecycleHooks, PersistentExtension, ServiceProvider {

    private val mfaService: MfaService = DefaultMfaService(
        config = config,
        timeZone = timeZone,
        hashingService = hashingService,
        secretEncryption = secretEncryption
    )

    private val sessionStore = MfaSessionStore(
        sessionExpiration = config.sessionExpiration,
        maxActiveSessions = config.maxActiveSessions
    )

    override val priority: Int = 65

    override fun tables(): List<Table> = listOf(
        MfaMethods,
        MfaChallenges,
        MfaBackupCodes
    )

    override suspend fun afterAuthentication(userId: UUID) {
        if (!config.requireMfa) {
            return
        }

        if (!mfaService.isMfaRequired(userId)) {
            return
        }

        val methods = mfaService.getMethods(userId)
        if (methods.isEmpty()) {
            return
        }

        val session = sessionStore.createSession(
            userId = userId,
            ipAddress = null,
            userAgent = null
        )

        throw KodexThrowable.Authorization.MfaRequired(
            sessionId = session.sessionId,
            availableMethods = methods.map { it.type.name }
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getService(type: KClass<T>): T? {
        return when (type) {
            MfaService::class -> mfaService as T
            else -> null
        }
    }

    public fun getSessionStore(): MfaSessionStore = sessionStore
}
