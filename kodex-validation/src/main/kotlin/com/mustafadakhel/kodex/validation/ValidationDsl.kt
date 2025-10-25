package com.mustafadakhel.kodex.validation

import com.mustafadakhel.kodex.routes.auth.RealmConfigScope

/**
 * Configure input validation and sanitization.
 *
 * Example:
 * ```kotlin
 * realm("admin") {
 *     validation {
 *         email {
 *             allowDisposable = false
 *         }
 *         phone {
 *             defaultRegion = "US"
 *             requireE164 = true
 *         }
 *         password {
 *             minLength = 12
 *             minScore = 3
 *         }
 *         customAttributes {
 *             allowedKeys = setOf("department", "employee_id")
 *         }
 *     }
 * }
 * ```
 */
public fun RealmConfigScope.validation(block: ValidationConfig.() -> Unit) {
    extension(ValidationConfig(), block)
}
