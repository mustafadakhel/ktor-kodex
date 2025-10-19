# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added

- Argon2id password hashing using BouncyCastle with configurable parameters and industry presets (Spring Security, Keycloak, OWASP minimum, balanced)
- `passwordHashing` DSL configuration block for realm-specific password hashing settings
- Comprehensive test coverage for Argon2id hashing service (25 tests)

### Changed

- Separated password hashing (Argon2id) from token hashing (SHA-256) for security

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
