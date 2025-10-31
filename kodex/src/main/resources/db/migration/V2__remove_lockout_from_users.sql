-- Remove lockout logic from core Users table
-- Lockout is now managed by the account-lockout extension via AccountLocks table

-- Remove lockout columns from users table
ALTER TABLE users DROP COLUMN IF EXISTS is_locked;
ALTER TABLE users DROP COLUMN IF EXISTS locked_until;

-- Create account_locks table in lockout extension
CREATE TABLE IF NOT EXISTS account_locks (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE,
    locked_until TIMESTAMP,
    reason VARCHAR(255) NOT NULL,
    locked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_account_locks_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Create index on user_id for faster lookups
CREATE INDEX IF NOT EXISTS idx_account_locks_user_id ON account_locks(user_id);
