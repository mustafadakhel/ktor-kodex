-- V6: Add MFA Trusted Devices
-- Creates table for MFA Trusted Devices feature:
-- - Allows users to trust devices to skip MFA challenges
-- - Tracks device fingerprints (SHA-256 hash of IP + User Agent)
-- - Supports device expiration and last used tracking
--
-- NOTE: This table is optional and only needed if you use the MFA extension
-- with trusted device support. If you don't use this feature, the table will
-- remain empty (no harm).

-- ============================================================================
-- MFA Trusted Devices Table
-- ============================================================================

-- MFA trusted devices: Tracks trusted devices per user
-- Device fingerprints are SHA-256 hashes of IP address + User Agent
-- Devices can be set to expire after N days (default 30)
CREATE TABLE IF NOT EXISTS mfa_trusted_devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    device_fingerprint VARCHAR(256) NOT NULL,
    device_name VARCHAR(128),
    ip_address VARCHAR(45),
    user_agent TEXT,
    trusted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP,
    expires_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Unique constraint: one fingerprint per user
CREATE UNIQUE INDEX IF NOT EXISTS idx_mfa_trusted_devices_unique
    ON mfa_trusted_devices(user_id, device_fingerprint);

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_mfa_trusted_devices_user_id ON mfa_trusted_devices(user_id);
CREATE INDEX IF NOT EXISTS idx_mfa_trusted_devices_fingerprint ON mfa_trusted_devices(device_fingerprint);
CREATE INDEX IF NOT EXISTS idx_mfa_trusted_devices_expires_at ON mfa_trusted_devices(expires_at);
