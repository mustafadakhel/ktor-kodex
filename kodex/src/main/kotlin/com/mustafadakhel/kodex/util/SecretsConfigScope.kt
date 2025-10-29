package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.Secrets
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import io.ktor.server.config.*
import io.ktor.utils.io.*
import java.security.SecureRandom

/**
 * Scope used to provide secrets for JWT signing and verification.
 */
public interface SecretsConfigScope {
    /** Supplies secrets directly in the configuration. */
    public fun RealmConfigScope.raw(vararg secrets: String)

    /** Reads secrets from [applicationConfig] using the provided [keys]. */
    public fun RealmConfigScope.fromEnv(applicationConfig: ApplicationConfig, vararg keys: String)

    /** Provider that exposes the list of secrets to the plugin. */
    public interface Provider {
        public val secrets: List<String>
    }
}

@KtorDsl
/** Default implementation of [SecretsConfigScope]. */
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
        return secret to index.toString()
    }

    internal fun secretForKid(kid: String): String? {
        val index = kid.toIntOrNull() ?: return null
        return secrets().getOrNull(index)
    }
}

