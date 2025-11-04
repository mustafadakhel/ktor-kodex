package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.extension.ExtensionRegistry
import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.service.auth.AuthService
import com.mustafadakhel.kodex.service.token.TokenService
import com.mustafadakhel.kodex.service.user.UserService
import kotlin.reflect.KClass

public class KodexRealmServices internal constructor(
    public val realm: Realm,
    public val users: UserService,
    public val auth: AuthService,
    public val tokens: TokenService,
    public val extensions: ExtensionRegistry
) {
    public inline fun <reified T : Any> getExtensionService(): T? {
        return extensions.getAllOfType(ServiceProvider::class)
            .firstNotNullOfOrNull { it.getService(T::class) }
    }
}
