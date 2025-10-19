# ktor-kodex

`ktor-kodex` is a production-ready Ktor plugin that provides enterprise-grade user management and JWT authentication. It features secure password hashing with Argon2id, automatic brute force protection, multi-realm support, and flexible role-based access control. Built for performance and security, it allows you to configure independent authentication realms and seamlessly integrate authentication into your Ktor application.

## Features

- **Multi realm support** – create independent authentication realms with their own secrets and claims
- **JWT token generation and verification** – access and refresh tokens signed using HS256
- **Secure password hashing** – Argon2id with configurable parameters and industry presets
- **Account lockout protection** – automatic brute force protection with configurable policies
- **Pluggable persistence** – tokens and user information are stored via Exposed and HikariCP
- **Role management** – roles are stored per realm and attached to issued tokens
- **Ktor routing helpers** – easily protect routes and retrieve the appropriate `KodexService`

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
