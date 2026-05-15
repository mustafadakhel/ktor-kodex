package com.mustafadakhel.kodex.token.cleanup

import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.isNotNull
import com.mustafadakhel.kodex.jdbc.less
import com.mustafadakhel.kodex.schema.KodexDatabase
import kotlinx.datetime.LocalDateTime

internal class TokenCleanupRepository(
    private val db: KodexDatabase,
    private val realmId: String,
) {

    private val tokens get() = db.core.tokens

    fun deleteExpiredTokens(before: LocalDateTime): Int = db.transaction {
        deleteFrom(tokens)
            .where { (tokens.realmId eq realmId) and (tokens.expiresAt less before) }
            .execute()
    }

    fun deleteRevokedTokens(before: LocalDateTime): Int = db.transaction {
        deleteFrom(tokens)
            .where { (tokens.realmId eq realmId) and (tokens.revoked eq true) and (tokens.createdAt less before) }
            .execute()
    }

    fun deleteRevokedFamilyTokens(before: LocalDateTime): Int = db.transaction {
        deleteFrom(tokens)
            .where {
                (tokens.realmId eq realmId) and
                    (tokens.revoked eq true) and
                    tokens.tokenFamily.isNotNull() and
                    (tokens.createdAt less before)
            }
            .execute()
    }
}
