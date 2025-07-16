package com.mustafadakhel.kodex.token

import com.mustafadakhel.kodex.model.TokenType

internal interface TokenVerifier {
    fun verify(
        decodedToken: DecodedToken,
        expectedType: TokenType,
    ): VerifiedToken
}

