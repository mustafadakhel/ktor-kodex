-- V4: Add Extension Tables
-- Creates tables for optional extension features:
-- - Verification extension (email/phone/custom attribute verification)
-- - Password reset extension (self-service password reset)
-- - Account lockout extension (enhanced lockout state tracking)
--
-- NOTE: These tables are optional and only needed if you install the corresponding extensions.
-- If you don't use an extension, the tables will remain empty (no harm).

-- ============================================================================
-- Verification Extension Tables
-- ============================================================================

-- Verifiable contacts: Tracks verification status for email, phone, and custom attributes
-- Allows multiple contact types per user (email + phone + custom attributes)
CREATE TABLE IF NOT EXISTS verifiable_contacts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    contact_type VARCHAR(50) NOT NULL,
    custom_attribute_key VARCHAR(128),
    contact_value VARCHAR(255) NOT NULL,
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Unique constraint: one contact per user per type (email, phone, or custom attribute key)
CREATE UNIQUE INDEX IF NOT EXISTS idx_verifiable_contacts_unique
    ON verifiable_contacts(user_id, contact_type, custom_attribute_key);

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_verifiable_contacts_user_id ON verifiable_contacts(user_id);
CREATE INDEX IF NOT EXISTS idx_verifiable_contacts_contact_value ON verifiable_contacts(contact_value);

-- Verification tokens: One-time codes for email/SMS/custom attribute verification
CREATE TABLE IF NOT EXISTS verification_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    contact_type VARCHAR(50) NOT NULL,
    custom_attribute_key VARCHAR(128),
    token VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Performance indexes
CREATE UNIQUE INDEX IF NOT EXISTS idx_verification_tokens_token ON verification_tokens(token);
CREATE INDEX IF NOT EXISTS idx_verification_tokens_user_id ON verification_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_verification_tokens_expires_at ON verification_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_verification_tokens_created_at ON verification_tokens(created_at);
CREATE INDEX IF NOT EXISTS idx_verification_tokens_used_at ON verification_tokens(used_at);

-- ============================================================================
-- Password Reset Extension Tables
-- ============================================================================

-- Password reset contacts: Cached contact info for password reset flow
-- Separate from users table for clean extension architecture
CREATE TABLE IF NOT EXISTS password_reset_contacts (
    user_id UUID NOT NULL,
    contact_type VARCHAR(50) NOT NULL,
    contact_value VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, contact_type)
);

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_password_reset_contacts_user_id ON password_reset_contacts(user_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_contacts_contact_value ON password_reset_contacts(contact_value);

-- Password reset tokens: One-time tokens for password reset flow
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    token VARCHAR(32) NOT NULL,
    contact_value VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    ip_address VARCHAR(45),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Performance indexes
CREATE UNIQUE INDEX IF NOT EXISTS idx_password_reset_tokens_token ON password_reset_tokens(token);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_created_at ON password_reset_tokens(created_at);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_expires_at ON password_reset_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_used_at ON password_reset_tokens(used_at);

-- ============================================================================
-- Account Lockout Extension Tables
-- ============================================================================

-- Account locks: Tracks account lockout state (enhancement to failed_login_attempts from V1)
-- Separate table to track actual lockout state vs. failed attempt history
-- Note: failed_login_attempts already exists in V1
CREATE TABLE IF NOT EXISTS account_locks (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE,
    locked_until TIMESTAMP,
    reason VARCHAR(255) NOT NULL,
    locked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Performance indexes
CREATE UNIQUE INDEX IF NOT EXISTS idx_account_locks_user_id ON account_locks(user_id);
CREATE INDEX IF NOT EXISTS idx_account_locks_locked_until ON account_locks(locked_until);
