package com.mustafadakhel.kodex.token.cleanup

import com.mustafadakhel.kodex.model.database.Tokens
import com.mustafadakhel.kodex.util.exposedTransaction
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere

internal class TokenCleanupRepository(private val realmId: String) {

    fun deleteExpiredTokens(before: LocalDateTime): Int = exposedTransaction {
        Tokens.deleteWhere { (Tokens.realmId eq realmId) and (Tokens.expiresAt less before) }
    }

    fun deleteRevokedTokens(before: LocalDateTime): Int = exposedTransaction {
        Tokens.deleteWhere { (Tokens.realmId eq realmId) and (Tokens.revoked eq true) and (Tokens.createdAt less before) }
    }

    fun deleteRevokedFamilyTokens(before: LocalDateTime): Int = exposedTransaction {
        Tokens.deleteWhere {
            (Tokens.realmId eq realmId) and
                (Tokens.revoked eq true) and
                Tokens.tokenFamily.isNotNull() and
                (Tokens.createdAt less before)
        }
    }
}
