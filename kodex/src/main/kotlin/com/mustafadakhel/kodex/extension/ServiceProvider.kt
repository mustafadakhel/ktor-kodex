package com.mustafadakhel.kodex.extension

import kotlin.reflect.KClass

/**
 * Extensions implementing this interface can expose services to library users
 * via [com.mustafadakhel.kodex.extensionService].
 */
public interface ServiceProvider : RealmExtension {
    public fun <T : Any> getService(type: KClass<T>): T?
}
