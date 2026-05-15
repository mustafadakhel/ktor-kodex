package com.mustafadakhel.kodex.token.cleanup

import com.mustafadakhel.kodex.schema.KodexDatabase
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere

internal class TokenCleanupRepository(
    private val db: KodexDatabase,
    private val realmId: String,
) {

    private val tokens get() = db.core.tokens

    fun deleteExpiredTokens(before: LocalDateTime): Int = db.transaction {
        tokens.deleteWhere { (tokens.realmId eq realmId) and (tokens.expiresAt less before) }
    }

    fun deleteRevokedTokens(before: LocalDateTime): Int = db.transaction {
        tokens.deleteWhere { (tokens.realmId eq realmId) and (tokens.revoked eq true) and (tokens.createdAt less before) }
    }

    fun deleteRevokedFamilyTokens(before: LocalDateTime): Int = db.transaction {
        tokens.deleteWhere {
            (tokens.realmId eq realmId) and
                (tokens.revoked eq true) and
                tokens.tokenFamily.isNotNull() and
                (tokens.createdAt less before)
        }
    }
}
