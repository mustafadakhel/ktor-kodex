package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.Secrets
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import io.ktor.server.config.*
import io.ktor.utils.io.*
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Scope used to provide secrets for JWT signing and verification.
 */
public interface SecretsConfigScope {
    public fun RealmConfigScope.raw(vararg secrets: String)

    public fun RealmConfigScope.fromEnv(applicationConfig: ApplicationConfig, vararg keys: String)

    public interface Provider {
        public val secrets: List<String>
    }
}

@KtorDsl
internal class SecretsConfig : SecretsConfigScope {

    private val secureRandom = SecureRandom()
    private var provider: SecretsConfigScope.Provider = RawProvider(Secrets.Raw(emptyList()))

    private class RawProvider(
        rawSecrets: Secrets.Raw
    ) : SecretsConfigScope.Provider {
        override val secrets = rawSecrets.secrets
    }

    private class EnvProvider(
        secretsFromEnv: Secrets.FromEnv,
    ) : SecretsConfigScope.Provider {
        override val secrets = secretsFromEnv.keys.map {
            secretsFromEnv.applicationConfig.property(it).getString()
        }
    }

    override fun RealmConfigScope.raw(vararg secrets: String) {
        require(secrets.isNotEmpty()) { "At least one secret must be provided" }
        secrets.forEachIndexed { index, secret ->
            require(secret.length >= 32) {
                "Secret at index $index is ${secret.length} characters. JWT secrets must be at least 32 characters for adequate security."
            }
        }
        this@SecretsConfig.provider = RawProvider(Secrets.Raw(secrets.toList()))
    }

    override fun RealmConfigScope.fromEnv(applicationConfig: ApplicationConfig, vararg keys: String) {
        this@SecretsConfig.provider = EnvProvider(Secrets.FromEnv(keys.toList(), applicationConfig))
    }

    internal fun secrets() = provider.secrets

    internal fun randomWithKid(): Pair<String, String> {
        val secrets = secrets()
        val index = secureRandom.nextInt(secrets.size)
        val secret = secrets[index]
        val kid = sha256Kid(secret)
        return secret to kid
    }

    internal fun secretForKid(kid: String): String? {
        return secrets().firstOrNull { sha256Kid(it) == kid }
    }

    private fun sha256Kid(secret: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(secret.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }
}
