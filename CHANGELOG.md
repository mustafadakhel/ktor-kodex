# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added

- Email and phone verification extension (`kodex-verification`) with token generation and verification
- Password reset extension (`kodex-password-reset`) with self-service password reset and rate limiting
- Metrics extension (`kodex-metrics`) with Micrometer integration
- Extension access via global `kodex()` function for service retrieval
- 71 integration tests for verification, password reset, audit, and lockout services
- Argon2id password hashing using BouncyCastle with configurable parameters and presets (Spring Security, Keycloak, OWASP minimum, balanced)
- `passwordHashing` DSL configuration block for realm-specific password hashing settings
- Account lockout mechanism against brute force attacks with configurable policies (strict, moderate, lenient, disabled)
- `accountLockout` DSL configuration block for realm-specific lockout settings
- Database persistence for failed login attempts and account lockouts
- Sliding window algorithm for tracking failed authentication attempts
- Automatic account locking after N failed attempts within configurable time window
- Manual unlock and clear failed attempts operations

### Changed

- Separated password hashing (Argon2id) from token hashing (SHA-256) for security
- Authentication flow refactored to eliminate try-catch for control flow
- `authenticateInternal()` now returns `Boolean` instead of throwing exceptions
- Failed login attempts now recorded for non-existent users to prevent enumeration attacks

### Fixed

- SQL query syntax errors in verification service (Exposed ORM `.select()` → `.selectAll().where {}`)
- Upsert reliability issues with nullable columns in composite unique indexes
- Rate limit bypass vulnerability in password reset for non-existent users (OWASP A03:2021)

### Performance

- Failed login handling optimized with scoped database cleanup per identifier instead of table-wide scan
- Authentication flow improved by removing try-catch overhead and exception throwing
- Reduced database lock contention through scoped queries
- Single `CurrentKotlinInstant` call per operation for timing consistency

### Breaking Changes

- SHA-256 removed for password hashing (Argon2id only)

## [0.1.7] - 2025-01-18

### Fixed

- Fixed eager H2 datasource creation preventing custom database configuration from being used. Previously, an H2 in-memory database was created immediately during plugin initialization, causing two connection pools when users configured PostgreSQL or other databases. Database tables were incorrectly created in H2 instead of the user's configured database. The datasource is now lazily initialized only when needed.

### Changed

- Database driver dependencies must be explicitly added to user projects. Kodex supports any JDBC-compatible database (PostgreSQL, MySQL, H2, etc.) but drivers are not bundled with the library.

## [0.1.6] - 2025-01-16

### Changed

- Use hashing service in login verification

## [0.1.5] - 2025-01-15

### Fixed

- Corrected the authenticate method to return the result of password comparison instead of always returning true

## [0.1.4] - 2025-01-14

### Changed

- Refactor authentication flow to separate password hashing and token generation

## [0.1.3] - 2025-01-13

### Fixed

- Fix role management issues
- Seed all realms roles correctly
- Expose a function to retrieve all the roles for a realm
