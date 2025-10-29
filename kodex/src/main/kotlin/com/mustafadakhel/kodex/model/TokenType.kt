package com.mustafadakhel.kodex.model

public sealed interface TokenType {
    public val claim: Claim.TokenType

    public val name: String

    public data object AccessToken : TokenType {
        override val name: String = "access"
        override val claim: Claim.TokenType = Claim.TokenType.AccessToken
    }

    public data object RefreshToken : TokenType {
        override val name: String = "refresh"
        override val claim: Claim.TokenType = Claim.TokenType.RefreshToken
    }

    public companion object {
        internal fun fromClaim(claim: Claim.TokenType): TokenType? {
            return when (claim) {
                Claim.TokenType.AccessToken -> AccessToken
                Claim.TokenType.RefreshToken -> RefreshToken
                is Claim.TokenType.Unknown -> null
            }
        }

        internal fun fromString(string: String?): TokenType {
            return when (string) {
                AccessToken.name -> AccessToken
                RefreshToken.name -> RefreshToken
                else -> throw IllegalArgumentException("Unknown token type: $string")
            }
        }
    }
}
