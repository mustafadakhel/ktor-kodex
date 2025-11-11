-- V7: Add realm_id to extension tables

ALTER TABLE mfa_methods ADD COLUMN realm_id VARCHAR(50) NOT NULL;
DROP INDEX IF EXISTS idx_mfa_methods_unique;
CREATE UNIQUE INDEX idx_mfa_methods_unique ON mfa_methods(realm_id, user_id, method_type, identifier);
CREATE INDEX idx_mfa_methods_realm_id ON mfa_methods(realm_id);
DROP INDEX IF EXISTS idx_mfa_methods_active;
CREATE INDEX idx_mfa_methods_active ON mfa_methods(realm_id, user_id, is_active);
DROP INDEX IF EXISTS idx_mfa_methods_primary;
CREATE INDEX idx_mfa_methods_primary ON mfa_methods(realm_id, user_id, is_primary);

ALTER TABLE mfa_challenges ADD COLUMN realm_id VARCHAR(50) NOT NULL;
CREATE INDEX idx_mfa_challenges_realm_id ON mfa_challenges(realm_id);
CREATE INDEX idx_mfa_challenges_realm_expires ON mfa_challenges(realm_id, expires_at);

ALTER TABLE mfa_backup_codes ADD COLUMN realm_id VARCHAR(50) NOT NULL;
CREATE INDEX idx_mfa_backup_codes_realm_id ON mfa_backup_codes(realm_id);
DROP INDEX IF EXISTS idx_mfa_backup_codes_used;
CREATE INDEX idx_mfa_backup_codes_used ON mfa_backup_codes(realm_id, user_id, used_at);

ALTER TABLE mfa_trusted_devices ADD COLUMN realm_id VARCHAR(50) NOT NULL;
DROP INDEX IF EXISTS idx_mfa_trusted_devices_unique;
CREATE UNIQUE INDEX idx_mfa_trusted_devices_unique ON mfa_trusted_devices(realm_id, user_id, device_fingerprint);
CREATE INDEX idx_mfa_trusted_devices_realm_id ON mfa_trusted_devices(realm_id);
CREATE INDEX idx_mfa_trusted_devices_realm_expires ON mfa_trusted_devices(realm_id, expires_at);

ALTER TABLE verifiable_contacts ADD COLUMN realm_id VARCHAR(50) NOT NULL;
DROP INDEX IF EXISTS idx_verifiable_contacts_unique;
CREATE UNIQUE INDEX idx_verifiable_contacts_unique ON verifiable_contacts(realm_id, user_id, contact_type, custom_attribute_key);
CREATE INDEX idx_verifiable_contacts_realm_id ON verifiable_contacts(realm_id);

ALTER TABLE verification_tokens ADD COLUMN realm_id VARCHAR(50) NOT NULL;
CREATE INDEX idx_verification_tokens_realm_id ON verification_tokens(realm_id);
CREATE INDEX idx_verification_tokens_realm_expires ON verification_tokens(realm_id, expires_at);

ALTER TABLE password_reset_contacts ADD COLUMN realm_id VARCHAR(50) NOT NULL;
ALTER TABLE password_reset_contacts DROP CONSTRAINT IF EXISTS password_reset_contacts_pkey;
ALTER TABLE password_reset_contacts ADD PRIMARY KEY (realm_id, user_id, contact_type);
CREATE INDEX idx_password_reset_contacts_realm_id ON password_reset_contacts(realm_id);

ALTER TABLE password_reset_tokens ADD COLUMN realm_id VARCHAR(50) NOT NULL;
CREATE INDEX idx_password_reset_tokens_realm_id ON password_reset_tokens(realm_id);
CREATE INDEX idx_password_reset_tokens_realm_expires ON password_reset_tokens(realm_id, expires_at);

ALTER TABLE failed_login_attempts ADD COLUMN realm_id VARCHAR(50) NOT NULL;
CREATE INDEX idx_failed_login_attempts_realm_id ON failed_login_attempts(realm_id);
CREATE INDEX idx_failed_login_attempts_realm_identifier ON failed_login_attempts(realm_id, identifier, attempted_at);

ALTER TABLE account_locks ADD COLUMN realm_id VARCHAR(50) NOT NULL;
DROP INDEX IF EXISTS idx_account_locks_user_id;
CREATE UNIQUE INDEX idx_account_locks_user_id ON account_locks(realm_id, user_id);
CREATE INDEX idx_account_locks_realm_id ON account_locks(realm_id);
