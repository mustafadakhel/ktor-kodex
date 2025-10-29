@file:Suppress("ConstPropertyName")

package com.mustafadakhel.kodex.model

import com.auth0.jwt.interfaces.DecodedJWT


public sealed interface Claim<T> {
    public val key: String
    public val value: T

    public sealed interface StringClaim : Claim<String?>

    public sealed interface BooleanClaim : Claim<Boolean?>

    public sealed interface NumberClaim : Claim<Number?>

    public sealed interface ListClaim<T> : Claim<List<T?>>

    public data class Realm(
        override val value: String?
    ) : StringClaim {
        override val key: String = Key

        public companion object {
            public const val Key: String = "realm"
        }
    }

    public data class Roles(
        override val value: List<String?> = emptyList()
    ) : ListClaim<String> {
        override val key: String = Key

        public companion object {
            public const val Key: String = "roles"
        }
    }

    public data class Issuer(
        override val value: String?
    ) : StringClaim {
        override val key: String = Key

        public companion object {
            public const val Key: String = "iss"
        }
    }

    public data class Audience(
        override val value: String?
    ) : StringClaim {
        override val key: String = Key

        public companion object {
            public const val Key: String = "aud"
        }
    }

    public data class Custom(
        override val value: Map<String, Any?>
    ) : Claim<Map<String, Any?>> {
        override val key: String = Key

        public companion object {
            public const val Key: String = "custom"
        }
    }

    public data class JwtId(
        override val value: String?
    ) : StringClaim {
        override val key: String = Key

        public companion object {
            public const val Key: String = "jti"
        }
    }

    public data class Subject(
        override val value: String?
    ) : StringClaim {
        override val key: String = Key

        public companion object {
            public const val Key: String = "sub"
        }
    }


    public data class ExpiresAt(
        override val value: Long?
    ) : NumberClaim {
        override val key: String = Key

        public companion object {
            public const val Key: String = "exp"
        }
    }

    public sealed interface TokenType : StringClaim {
        override val key: String get() = Key

        public data object AccessToken : TokenType {
            override val value: String = com.mustafadakhel.kodex.model.TokenType.AccessToken.name
        }

        public data object RefreshToken : TokenType {
            override val value: String = com.mustafadakhel.kodex.model.TokenType.RefreshToken.name
        }

        public data class Unknown(override val value: String?) : TokenType

        public companion object {
            public const val Key: String = "token_type"
        }
    }

    public data class Unknown(
        override val key: String,
        override val value: String?
    ) : Claim<String?>

    public companion object {
        internal fun from(key: String, value: com.auth0.jwt.interfaces.Claim?): Claim<*> {
            return when (key) {
                Roles.Key -> Roles(value?.asList(String::class.java) ?: emptyList())
                Realm.Key -> Realm(value?.asString())
                Issuer.Key -> Issuer(value?.asString())
                Audience.Key -> Audience(value?.asString())
                Custom.Key -> Custom(value?.asMap() ?: emptyMap())
                JwtId.Key -> JwtId(value?.asString())
                Subject.Key -> Subject(value?.asString())
                TokenType.Key -> when (value?.asString()) {
                    TokenType.AccessToken.value -> TokenType.AccessToken
                    TokenType.RefreshToken.value -> TokenType.RefreshToken
                    else -> TokenType.Unknown(value?.asString())
                }

                else -> Unknown(
                    key = key,
                    value = value?.asString()
                )
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T : Claim<*>> DecodedJWT.claim(key: String): T? {
    return this.claims[key]?.let {
        Claim.from(key, it) as? T?
    }
}