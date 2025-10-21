# ktor-kodex

`ktor-kodex` is a production-ready Ktor plugin that provides enterprise-grade user management and JWT authentication. It features secure password hashing with Argon2id, automatic brute force protection, comprehensive audit logging, multi-realm support, and flexible role-based access control. Built for performance and security, it allows you to configure independent authentication realms and seamlessly integrate authentication into your Ktor application.

## Features

- **Multi realm support** – create independent authentication realms with their own secrets and claims
- **JWT token generation and verification** – access and refresh tokens signed using HS256
- **Secure password hashing** – Argon2id with configurable parameters and industry presets
- **Account lockout protection** – automatic brute force protection with configurable policies
- **Token rotation** – automatic refresh token rotation with replay attack detection
- **Input validation & sanitization** – optional RFC 5322 email, E.164 phone, password strength scoring, XSS protection
- **Audit logging** – comprehensive event tracking with query and export capabilities for compliance
- **Pluggable persistence** – tokens and user information are stored via Exposed and HikariCP
- **Role management** – roles are stored per realm and attached to issued tokens
- **Ktor routing helpers** – easily protect routes and retrieve the appropriate `KodexService`
- **Extension system** – modular architecture for adding custom functionality via lifecycle hooks

## Extension system

Kodex features a flexible extension system that allows you to customize and extend authentication behavior without modifying core code. Extensions hook into user lifecycle events like login attempts, user creation, and data updates.

**Built-in extensions:**
- **Account Lockout** (`kodex-lockout`) – Brute force protection via failed attempt tracking
- **Input Validation** (`kodex-validation`) – Email, phone, password validation and XSS protection
- **Audit Logging** (`kodex-audit`) – Comprehensive event tracking for compliance

**How extensions work:**

Extensions implement lifecycle hooks that execute at specific points in the authentication flow:

- `beforeLogin()` – Validate/modify login identifier before authentication
- `afterLoginFailure()` – React to failed login attempts (e.g., record failed attempt)
- `beforeUserCreate()` – Validate/transform user data before creation
- `beforeUserUpdate()` – Validate/transform update data before persistence
- `beforeCustomAttributesUpdate()` – Validate/sanitize custom attributes

**Creating a custom extension:**

```kotlin
class RateLimitExtension internal constructor(
    private val requestsPerMinute: Int
) : UserLifecycleHooks {

    private val attempts = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun beforeLogin(identifier: String): String {
        val count = attempts.computeIfAbsent(identifier) { AtomicInteger(0) }
        if (count.incrementAndGet() > requestsPerMinute) {
            throw KodexThrowable.Authorization.RateLimitExceeded
        }
        return identifier
    }

    override suspend fun afterLoginFailure(identifier: String) {
        // Extension point for tracking failures
    }
}
```

**Registering extensions:**

Extensions are configured per realm during plugin installation:

```kotlin
realm("admin") {
    // Built-in extensions are enabled via DSL
    accountLockout {
        policy = AccountLockoutPolicy.moderate()
    }

    validation {
        email { allowDisposable = false }
        password { minScore = 3 }
    }

    // Custom extensions can be added programmatically
    // extension(RateLimitExtension(requestsPerMinute = 60))
}
```

**Extension capabilities:**

- **Database access:** Extensions can manage their own tables using `kodexTransaction()` for safe database operations
- **Hook chaining:** Multiple extensions execute in registration order
- **Type safety:** Strongly-typed hook interfaces prevent runtime errors
- **Validation hooks:** Return transformed data or throw exceptions to reject operations
- **Async support:** All hooks are suspendable for non-blocking operations

**Example: Account lockout flow**

```
1. User attempts login → beforeLogin() checks if account is locked
2. If locked → throw AccountLocked exception
3. Authentication fails → afterLoginFailure() records failed attempt
4. If threshold exceeded → Account automatically locked for duration
5. Next login attempt → beforeLogin() detects lockout, blocks access
```

## Getting started

Add the library to your project:

In your `build.gradle.kts` file:

```kotlin
// Make sure to include the maven central repository
repositories {
    mavenCentral()
}
```

In your `dependencies` block:

```kotlin
implementation("com.mustafadakhel.kodex:kodex:latest-version")
```

**Optional: Input validation module**

For input validation and sanitization features, add the validation module:

```kotlin
implementation("com.mustafadakhel.kodex:kodex-validation:latest-version")
```

The validation module is completely optional. Without it, you are responsible for validating and sanitizing user input yourself.

### Database driver

Kodex supports any JDBC-compatible database. You must add the appropriate driver to your project dependencies:

**PostgreSQL:**

```kotlin
runtimeOnly("org.postgresql:postgresql:42.7.2")
```

**MySQL:**

```kotlin
runtimeOnly("com.mysql:mysql-connector-j:8.3.0")
```

**H2 (for testing):**

```kotlin
runtimeOnly("com.h2database:h2:2.3.232")
```

Kodex uses the driver you provide through the `database` configuration block. Install and configure the plugin inside your `Application` module. The example below mirrors the setup found in the `sample` project:

```kotlin
fun Application.configureKodex() {
    install(Kodex) {
        database { // HikariCP configuration scope
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = "jdbc:postgresql://localhost/db"
            username = "dbuser"
            password = "dbpass"
        }
        realm("admin") {
            // configure the admin realm
        }
    }
}
```

Once installed you can obtain an `KodexService` for a realm and use it to create users or issue tokens. Routes can be
protected using `authenticateFor` and the additional helpers provided by the plugin. Inside an authenticated block the
current `KodexPrincipal` is available via `call.kodex`:

```kotlin
routing {
    authenticateFor(Realm("admin")) {
        get("/protected") {
            val principal = call.kodex
            call.respondText("ok")
        }
    }
}
```

The returned `KodexPrincipal` exposes several details about the authenticated user:

- `userId` – the `UUID` of the user
- `type` – the `TokenType` of the validated token
- `realm` – the `Realm` from which the token originates
- `roles` – a `List<Role>` assigned to the user
- `token` – the raw JWT string when available

## Plugin configuration

Each realm can define its own secrets, claims, token validity and roles. Secrets are mandatory and can either be
provided directly or read from your application configuration. Claims specify the issuer and audience of the generated
tokens and you may attach additional static claims. Token validity controls how long access and refresh tokens remain
valid and whether they should be persisted. Example:

```kotlin
realm("admin") {
    secrets {
        raw("secret1", "secret2")
        // or load from environment / application.conf
        // fromEnv(application.config, "JWT_SECRET")
    }
    claims {
        issuer("https://my-app")
        audience("ktor-kodex")
        claim("scope", "admin")
    }
    roles {
        role("admin")
        role("moderator")
    }
    tokenValidity {
        access(1.hours)
        refresh(30.days)
        persist(TokenType.AccessToken, true)
    }
    passwordHashing {
        algorithm = Argon2id.balanced()
    }
    accountLockout {
        policy = AccountLockoutPolicy.moderate()  // 5 attempts, 15min window, 30min lockout
    }
    tokenRotation {
        policy = TokenRotationPolicy.balanced()   // Automatic rotation with 5s grace period
    }
}
```

Database connectivity is configured in the `database` block where a `HikariConfig` is available. After installation you
can obtain services using `application.kodex.serviceOf(realm)`.

### Password hashing

Kodex uses Argon2id for secure password hashing with industry-standard presets:

```kotlin
passwordHashing {
    algorithm = Argon2id.springSecurity()  // Spring Security defaults
    // or
    algorithm = Argon2id.keycloak()        // Keycloak defaults
    // or
    algorithm = Argon2id.owaspMinimum()    // OWASP minimum recommendations
    // or
    algorithm = Argon2id.balanced()        // Balanced security/performance (default)
}
```

You can also customize parameters:

```kotlin
passwordHashing {
    algorithm = Argon2id(
        saltLength = 16,
        hashLength = 32,
        parallelism = 1,
        memory = 65536,      // 64 MB
        iterations = 3
    )
}
```

### Account lockout

Protect against brute force attacks with automatic account lockout:

```kotlin
accountLockout {
    policy = AccountLockoutPolicy.strict()    // 3 attempts, 15min window, 1hr lockout
    // or
    policy = AccountLockoutPolicy.moderate()  // 5 attempts, 15min window, 30min lockout (default)
    // or
    policy = AccountLockoutPolicy.lenient()   // 10 attempts, 30min window, 15min lockout
    // or
    policy = AccountLockoutPolicy.disabled()  // No lockout
}
```

Custom policy:

```kotlin
accountLockout {
    policy = AccountLockoutPolicy(
        maxFailedAttempts = 5,
        attemptWindow = 15.minutes,      // Track attempts within this window
        lockoutDuration = 30.minutes     // Lock for this duration
    )
}
```

**How it works:**
- Failed login attempts are tracked per identifier (email/phone)
- After N failures within the attempt window, the account locks automatically
- Lockout expires after the configured duration
- Successful login clears all failed attempts
- Prevents username enumeration by tracking non-existent users

### Token rotation

Automatic refresh token rotation protects against token theft and replay attacks:

```kotlin
tokenRotation {
    policy = TokenRotationPolicy.balanced()  // 5s grace period (default)
    // or
    policy = TokenRotationPolicy.strict()    // No grace period
    // or
    policy = TokenRotationPolicy.lenient()   // 10s grace period, no replay detection
    // or
    policy = TokenRotationPolicy.disabled()  // Legacy behavior (not recommended)
}
```

Custom policy:

```kotlin
tokenRotation {
    policy = TokenRotationPolicy(
        enabled = true,
        detectReplayAttacks = true,
        revokeOnReplay = true,
        gracePeriod = 5.seconds
    )
}
```

**How it works:**
- Each refresh creates a new access + refresh token pair
- Old refresh token is marked as used and cannot be reused
- All rotated tokens share a token family ID for lineage tracking
- **Replay detection:** If a used token is presented again after the grace period, it triggers a security violation
- **Automatic revocation:** When replay is detected, the entire token family is revoked
- **Audit logging:** Replay attacks are automatically logged as security violations
- **Grace period:** Prevents false positives from network retries (configurable, default 5 seconds)

The rotation is transparent to your application code - simply call `refresh()` as usual:

```kotlin
val tokenPair = kodexService.refresh(userId, refreshToken)
// Returns new access and refresh tokens
// Old refresh token is now single-use and cannot be replayed
```

### Input validation

**Input validation is optional and requires the `kodex-validation` module.**

First, add the dependency to your project:

```kotlin
implementation("com.mustafadakhel.kodex:kodex-validation:latest-version")
```

Without the validation module, you are responsible for validating and sanitizing user input yourself.

With the validation module, you can enable comprehensive input validation and sanitization that protects against XSS attacks, prevents data corruption, and enforces security policies:

```kotlin
realm("admin") {
    validation {
        email {
            allowDisposable = false  // Block temporary email services (default: false)
        }

        phone {
            defaultRegion = "ZZ"     // Unknown region = require international format
            requireE164 = true       // Must start with + and country code (default: true)
        }

        password {
            minLength = 12           // Minimum password length (default: 8)
            minScore = 3             // 0=very weak, 4=very strong (default: 2)
            commonPasswords = customSet  // Optional: override default dictionary
        }

        customAttributes {
            maxKeyLength = 128       // Prevent DoS (default: 128)
            maxValueLength = 4096    // Prevent DoS (default: 4096)
            maxAttributes = 50       // Limit per user (default: 50)
            allowedKeys = setOf("department", "employee_id")  // Optional allowlist
        }
    }
}
```

> **⚠️ Security Warning:** If you don't configure the `validation {}` block, validation is **disabled**. You must handle:
> - Input sanitization to prevent XSS attacks
> - Email and phone format validation
> - Password strength enforcement
> - Custom attribute sanitization and length limits

**What's validated when enabled:**

- **Email**: RFC 5322 format, length limits (320 chars), local part (64 chars), domain (255 chars), disposable domain detection (including subdomains)
- **Phone**: E.164 international format using Google's libphonenumber library
- **Password strength**: zxcvbn-inspired entropy scoring, common password dictionary (~120 passwords), pattern detection (sequential/repeated/keyboard), locale-safe comparison
- **Custom attributes**: Key format (alphanumeric + `_`, `-`, `.`), reserved key protection, null byte detection, XSS protection via HTML entity escaping, all control characters removed

**Validation is automatically enforced** on all user operations (when configured):

```kotlin
// Validation happens automatically
val user = kodexService.createUser(
    email = "user@example.com",    // Validated against RFC 5322
    phone = "+14155552671",         // Validated as E.164
    password = "MyStr0ngP@ssw0rd!",  // Strength scored 0-4
    customAttributes = mapOf(       // Sanitized for XSS
        "department" to "Engineering"
    )
)

// Throws KodexThrowable.Validation.* exceptions on invalid input:
// - InvalidEmail(email, errors)
// - InvalidPhone(phone, errors)
// - WeakPassword(score, feedback)
// - InvalidInput(field, errors)
```

**Password strength analysis:**

```kotlin
val strength = kodexService.analyzePasswordStrength("password123")
// PasswordStrength(
//     score = 0,              // 0-4 (very weak to very strong)
//     entropy = 28.2,         // Bits of entropy
//     crackTime = 0.001s,     // Estimated crack time
//     feedback = [
//         "This password is commonly used and easily guessed",
//         "Use at least 12 characters for better security",
//         "Add special characters (!@#$%)"
//     ],
//     isAcceptable = false
// )
```

### Audit logging

Comprehensive audit logging for compliance and security monitoring. All authentication events are automatically tracked, and you can log custom events using a flexible DSL.

**Configuration:**

```kotlin
install(Kodex) {
    audit {
        enabled = true
        queueCapacity = 10000
        batchSize = 100
        flushInterval = 5.seconds
        retentionPeriod = 90.days
    }
}
```

**Automatic audit events:**
- Login success and failure (with IP address and reason)
- User creation
- Account lockout events

**Query and export:**

```kotlin
val auditService = application.kodex.auditService

// Query events with filters
val events = auditService.query(AuditFilter(
    eventTypes = listOf(AuditEvents.LOGIN_SUCCESS, AuditEvents.LOGIN_FAILED),
    startDate = yesterday,
    endDate = now,
    actorId = userId,
    limit = 100
))

// Export to JSON or CSV
val jsonExport = auditService.export(filter, ExportFormat.JSON)
val csvExport = auditService.export(filter, ExportFormat.CSV)

// Count events
val failedLogins = auditService.count(AuditFilter(
    eventTypes = listOf(AuditEvents.LOGIN_FAILED),
    startDate = today
))
```

**Custom events using the DSL:**

```kotlin
// Flexible DSL for custom events
auditService.audit(realmId = "admin") {
    event(AuditEvents.PERMISSION_CHANGED)
    actor(adminUserId, ipAddress = "192.168.1.1", userAgent = "Mozilla/5.0")
    target(targetUserId, "user")
    success()
    context {
        "oldRoles" to listOf("user")
        "newRoles" to listOf("user", "admin")
        "reason" to "Promotion"
    }
}

// Extension functions for common patterns
auditService.auditLoginSuccess(
    realmId = "admin",
    userId = userId,
    ipAddress = call.request.origin.remoteHost,
    userAgent = call.request.userAgent(),
    method = "email"
)
```

**Available event types:**

The `AuditEvents` object provides 50+ predefined event type constants including:
- Authentication: `LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGOUT`, `TOKEN_REFRESHED`
- User management: `USER_CREATED`, `USER_UPDATED`, `USER_DELETED`, `EMAIL_VERIFIED`
- Authorization: `PERMISSION_GRANTED`, `PERMISSION_DENIED`, `ROLE_ASSIGNED`
- Security: `PASSWORD_CHANGED`, `ACCOUNT_LOCKED`, `ACCOUNT_UNLOCKED`, `MFA_ENABLED`
- Data access: `DATA_ACCESSED`, `DATA_MODIFIED`, `DATA_DELETED`, `DATA_EXPORTED`

You can also use custom event type strings for application-specific events.

### Routing helpers

The plugin ships with utilities for building routes that automatically supply the authenticated user ID. `me {}`
registers a route at `/me` while `id {}` binds to `/{id}`. Both return an `AuthorizedRoute` where the handler receives
the resolved `UUID`:

```kotlin
routing {
    authenticateFor(Realm("admin")) {
        me { userId ->
            // handle requests for the authenticated user
        }
        id {
            get { id ->
                // access a user by path parameter
            }
        }
    }
}
```

## Running the sample

The sample uses an in-memory H2 database. Before starting the server provide the database password using the
`DB_PASSWORD` environment variable:

```bash
export DB_PASSWORD=YourStrongPassword
./gradlew :sample:run
```

Point your browser to `http://localhost:8080` and interact with the authentication routes defined in
`sample/Application.kt`.

## Running the tests

Unit tests for the plugin reside under the `kodex` module. Execute them with:

```bash
./gradlew :kodex:test
```

> **Note**: Gradle requires network access to download dependencies on the first run. The execution environment used for
> this README might block outbound connections which can cause the command to fail.

## Realm roles and additional roles

Tokens generated by this library contain a `roles` claim. The first entry is the realm role and is prefixed with
`realm:` to distinguish it from any extra roles assigned to the user. When validating a token the library checks all
role claims. A token remains valid only if the user still possesses at least one of the roles listed in the claim.
