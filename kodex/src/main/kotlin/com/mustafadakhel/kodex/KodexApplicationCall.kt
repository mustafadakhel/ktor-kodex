package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.Realm
import io.ktor.server.application.*

public inline fun <reified T : Any> ApplicationCall.extensionService(realm: Realm): T? {
    return application.kodex.servicesOf(realm).getExtensionService()
}
