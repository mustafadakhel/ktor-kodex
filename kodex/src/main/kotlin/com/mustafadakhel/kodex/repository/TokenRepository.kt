package com.mustafadakhel.kodex.repository

import com.mustafadakhel.kodex.model.database.PersistedToken
import java.util.*

internal interface TokenRepository {
    fun storeToken(token: PersistedToken): UUID
    fun findToken(tokenId: UUID): PersistedToken?
    fun deleteToken(tokenId: UUID)
    fun deleteToken(tokenHash: String)
    fun revokeTokens(userId: UUID)
    fun revokeToken(tokenHash: String)
}
