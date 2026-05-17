package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.lockout.schema.LockoutSchema
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import io.ktor.utils.io.*

@KtorDsl
public class AccountLockoutConfig : ExtensionConfig() {

    /** The lockout policy to use (default: moderate - 5 attempts, 30 min lockout) */
    public var policy: AccountLockoutPolicy = AccountLockoutPolicy.moderate()

    override fun schema(tablePrefix: String): ExtensionSchema = LockoutSchema(tablePrefix)

    override fun build(context: ExtensionContext, db: KodexDatabase): AccountLockoutExtension {
        val schema = db.schema<LockoutSchema>()
        val service = accountLockoutService(db, schema, policy, context.timeZone, context.realm.name)
        return AccountLockoutExtension(service, context.timeZone)
    }
}
