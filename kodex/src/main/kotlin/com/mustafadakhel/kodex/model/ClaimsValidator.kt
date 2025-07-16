package com.mustafadakhel.kodex.model

internal interface ClaimsValidator {
    fun validate(
        claims: List<Claim<*>>,
        expectedType: TokenType,
        expectedRoles: List<String>,
    ): Boolean
}