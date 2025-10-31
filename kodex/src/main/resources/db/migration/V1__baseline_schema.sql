-- Kodex Database Schema V1 - Baseline
-- This migration creates all core tables for the Kodex authentication library
--
-- DATABASE COMPATIBILITY:
-- This migration uses H2/MySQL syntax. For other databases, customize as needed:
-- - PostgreSQL: Change INT AUTO_INCREMENT → SERIAL or INT GENERATED ALWAYS AS IDENTITY
-- - PostgreSQL: UUID type is native, no changes needed
-- - SQLite: Change AUTO_INCREMENT → AUTOINCREMENT
--
-- NOTE: Most users should use the default SchemaUtils.create() behavior
-- which automatically adapts to your database. This migration is optional
-- for users who prefer version-controlled schema management.

-- ============================================================================
-- Core User Tables
-- ============================================================================

-- Users table: Core user authentication data
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    phone_number VARCHAR(20) UNIQUE,
    email VARCHAR(255) UNIQUE,
    last_login_at TIMESTAMP,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    is_locked BOOLEAN NOT NULL DEFAULT FALSE,
    locked_until TIMESTAMP
);

-- Roles table: Role definitions
CREATE TABLE IF NOT EXISTS roles (
    name VARCHAR(50) PRIMARY KEY,
    description TEXT
);

-- User-Role junction table: Many-to-many relationship
CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID NOT NULL,
    role_id VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(name) ON DELETE CASCADE
);

-- User profiles: Extended user information
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id UUID PRIMARY KEY,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    address TEXT,
    profile_picture TEXT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- User custom attributes: Flexible key-value storage per user
-- Secured against SQL injection with strict key validation
CREATE TABLE IF NOT EXISTS user_custom_attributes (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id UUID NOT NULL,
    key VARCHAR(100) NOT NULL,
    value TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE (user_id, key)
);

-- ============================================================================
-- Token Management Tables
-- ============================================================================

-- Tokens: JWT access and refresh tokens with rotation support
CREATE TABLE IF NOT EXISTS tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    token_hash TEXT NOT NULL,
    type VARCHAR(16) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    token_family UUID,
    parent_token_id UUID,
    first_used_at TIMESTAMP,
    last_used_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ============================================================================
-- Audit & Security Tables
-- ============================================================================

-- Audit events: Immutable append-only audit log
CREATE TABLE IF NOT EXISTS audit_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    actor_id UUID,
    actor_type VARCHAR(20) NOT NULL,
    target_id UUID,
    target_type VARCHAR(100),
    result VARCHAR(20) NOT NULL,
    metadata TEXT NOT NULL,
    realm_id VARCHAR(50) NOT NULL,
    session_id UUID
);

-- Failed login attempts: Track authentication failures for lockout/throttling detection
CREATE TABLE IF NOT EXISTS failed_login_attempts (
    id UUID PRIMARY KEY,
    identifier VARCHAR(255) NOT NULL,
    user_id UUID,
    ip_address VARCHAR(45),
    attempted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(255) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ============================================================================
-- Indexes for Performance
-- ============================================================================

-- Users table indexes
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_phone_number ON users(phone_number);

-- Tokens table indexes
CREATE INDEX IF NOT EXISTS idx_tokens_user_id ON tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_tokens_token_hash ON tokens(token_hash(255));
CREATE INDEX IF NOT EXISTS idx_tokens_token_family ON tokens(token_family);

-- Audit events indexes
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_events(timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_event_type ON audit_events(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_actor_id ON audit_events(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_target_id ON audit_events(target_id);
CREATE INDEX IF NOT EXISTS idx_audit_realm_id ON audit_events(realm_id);

-- Failed login attempts indexes
CREATE INDEX IF NOT EXISTS idx_failed_attempts_identifier ON failed_login_attempts(identifier);
CREATE INDEX IF NOT EXISTS idx_failed_attempts_user_id ON failed_login_attempts(user_id);
CREATE INDEX IF NOT EXISTS idx_failed_attempts_ip_address ON failed_login_attempts(ip_address);
CREATE INDEX IF NOT EXISTS idx_failed_attempts_attempted_at ON failed_login_attempts(attempted_at);
