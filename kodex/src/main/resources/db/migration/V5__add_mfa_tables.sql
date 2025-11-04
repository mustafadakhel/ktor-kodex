-- V5: Add MFA Tables
-- Creates tables for Multi-Factor Authentication (MFA) extension:
-- - MFA methods (enrolled authentication methods per user)
-- - MFA challenges (active email OTP challenges)
-- - MFA backup codes (recovery codes for account recovery)
--
-- NOTE: These tables are optional and only needed if you install the MFA extension.
-- If you don't use the MFA extension, the tables will remain empty (no harm).

-- ============================================================================
-- MFA Extension Tables
-- ============================================================================

-- MFA methods: Tracks enrolled MFA methods per user (EMAIL, TOTP)
-- Stores encrypted TOTP secrets and method metadata
CREATE TABLE IF NOT EXISTS mfa_methods (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    method_type VARCHAR(50) NOT NULL,
    identifier VARCHAR(255),
    encrypted_secret TEXT,
    encryption_nonce VARCHAR(32),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Unique constraint: one method per user per type+identifier combination
CREATE UNIQUE INDEX IF NOT EXISTS idx_mfa_methods_unique
    ON mfa_methods(user_id, method_type, identifier);

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_mfa_methods_user_id ON mfa_methods(user_id);
CREATE INDEX IF NOT EXISTS idx_mfa_methods_type ON mfa_methods(method_type);
CREATE INDEX IF NOT EXISTS idx_mfa_methods_active ON mfa_methods(user_id, is_active);
CREATE INDEX IF NOT EXISTS idx_mfa_methods_primary ON mfa_methods(user_id, is_primary);

-- MFA challenges: Active email OTP challenges (short-lived)
-- Tracks challenge codes, expiration, and verification attempts
CREATE TABLE IF NOT EXISTS mfa_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    method_id UUID NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    verified_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (method_id) REFERENCES mfa_methods(id) ON DELETE CASCADE
);

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_mfa_challenges_user_id ON mfa_challenges(user_id);
CREATE INDEX IF NOT EXISTS idx_mfa_challenges_method_id ON mfa_challenges(method_id);
CREATE INDEX IF NOT EXISTS idx_mfa_challenges_expires_at ON mfa_challenges(expires_at);
CREATE INDEX IF NOT EXISTS idx_mfa_challenges_verified ON mfa_challenges(verified_at);

-- MFA backup codes: Recovery codes for account recovery (10 per user)
-- Hashed using Argon2id, single-use only
CREATE TABLE IF NOT EXISTS mfa_backup_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_mfa_backup_codes_user_id ON mfa_backup_codes(user_id);
CREATE INDEX IF NOT EXISTS idx_mfa_backup_codes_used ON mfa_backup_codes(user_id, used_at);
