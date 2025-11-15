-- V8: Add Session Management Tables
-- Creates tables for Session Management extension:
-- - Tracks active user sessions across multiple devices
-- - Stores session history for audit and analytics
-- - Supports device fingerprinting and geolocation tracking
-- - Enables session revocation and concurrent session limits
--
-- NOTE: These tables are optional and only needed if you use the Sessions extension.
-- If you don't use this feature, the tables will remain empty (no harm).

-- ============================================================================
-- Sessions Table
-- ============================================================================

-- Active sessions: Tracks user sessions across devices
-- Each session is tied to a token family (refresh token rotation group)
-- Device fingerprints are SHA-256 hashes of IP address + User Agent
-- Sessions expire based on refresh token validity
CREATE TABLE IF NOT EXISTS sessions (
    id UUID PRIMARY KEY,
    realm_id VARCHAR(50) NOT NULL,
    user_id UUID NOT NULL,
    token_family UUID NOT NULL UNIQUE,
    device_fingerprint VARCHAR(256) NOT NULL,
    device_name VARCHAR(128),
    ip_address VARCHAR(45),
    user_agent TEXT,
    location VARCHAR(100),
    latitude DECIMAL(10, 7),
    longitude DECIMAL(10, 7),
    created_at TIMESTAMP NOT NULL,
    last_activity_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    revoked_at TIMESTAMP,
    revoked_reason VARCHAR(255),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Performance indexes (single column)
CREATE INDEX IF NOT EXISTS idx_sessions_realm_id ON sessions(realm_id);
CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_device_fingerprint ON sessions(device_fingerprint);
CREATE INDEX IF NOT EXISTS idx_sessions_created_at ON sessions(created_at);
CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON sessions(expires_at);

-- Composite indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_sessions_user_created ON sessions(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_user_status ON sessions(user_id, status);
CREATE INDEX IF NOT EXISTS idx_sessions_user_activity ON sessions(user_id, last_activity_at DESC);

-- ============================================================================
-- Session History Table
-- ============================================================================

-- Session history: Archives past sessions for audit and analytics
-- Stores metadata about completed/revoked sessions
-- Used for security monitoring and user activity reports
CREATE TABLE IF NOT EXISTS session_history (
    id UUID PRIMARY KEY,
    realm_id VARCHAR(50) NOT NULL,
    user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    device_name VARCHAR(128),
    ip_address VARCHAR(45),
    location VARCHAR(100),
    login_at TIMESTAMP NOT NULL,
    logout_at TIMESTAMP,
    end_reason VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Performance indexes (single column)
CREATE INDEX IF NOT EXISTS idx_session_history_realm_id ON session_history(realm_id);
CREATE INDEX IF NOT EXISTS idx_session_history_user_id ON session_history(user_id);
CREATE INDEX IF NOT EXISTS idx_session_history_login_at ON session_history(login_at);

-- Composite indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_session_history_user_login ON session_history(user_id, login_at DESC);
