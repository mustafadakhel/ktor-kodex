-- Add CASCADE foreign keys to extension tables
-- This ensures that when a user is deleted, all related extension data is automatically cleaned up

-- Add foreign key to failed_login_attempts table
-- Note: Drop and recreate to add CASCADE (ALTER doesn't support changing CASCADE in all databases)
DO $$
BEGIN
    -- Check if constraint exists, drop it if it does
    IF EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_failed_login_attempts_user_id'
        AND table_name = 'failed_login_attempts'
    ) THEN
        ALTER TABLE failed_login_attempts DROP CONSTRAINT fk_failed_login_attempts_user_id;
    END IF;

    -- Add the foreign key with CASCADE
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_failed_login_attempts_user_id'
        AND table_name = 'failed_login_attempts'
    ) THEN
        ALTER TABLE failed_login_attempts
        ADD CONSTRAINT fk_failed_login_attempts_user_id
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
END $$;

-- Add foreign key to verifiable_contacts table (if it exists)
DO $$
BEGIN
    -- Only add if table exists (verification extension may not be installed)
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_name = 'verifiable_contacts'
    ) THEN
        -- Check if constraint exists, drop it if it does
        IF EXISTS (
            SELECT 1
            FROM information_schema.table_constraints
            WHERE constraint_name = 'fk_verifiable_contacts_user_id'
            AND table_name = 'verifiable_contacts'
        ) THEN
            ALTER TABLE verifiable_contacts DROP CONSTRAINT fk_verifiable_contacts_user_id;
        END IF;

        -- Add the foreign key with CASCADE
        IF NOT EXISTS (
            SELECT 1
            FROM information_schema.table_constraints
            WHERE constraint_name = 'fk_verifiable_contacts_user_id'
            AND table_name = 'verifiable_contacts'
        ) THEN
            ALTER TABLE verifiable_contacts
            ADD CONSTRAINT fk_verifiable_contacts_user_id
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
        END IF;
    END IF;
END $$;

-- Add foreign key to verification_tokens table (if it exists)
DO $$
BEGIN
    -- Only add if table exists (verification extension may not be installed)
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_name = 'verification_tokens'
    ) THEN
        -- Check if constraint exists, drop it if it does
        IF EXISTS (
            SELECT 1
            FROM information_schema.table_constraints
            WHERE constraint_name = 'fk_verification_tokens_user_id'
            AND table_name = 'verification_tokens'
        ) THEN
            ALTER TABLE verification_tokens DROP CONSTRAINT fk_verification_tokens_user_id;
        END IF;

        -- Add the foreign key with CASCADE
        IF NOT EXISTS (
            SELECT 1
            FROM information_schema.table_constraints
            WHERE constraint_name = 'fk_verification_tokens_user_id'
            AND table_name = 'verification_tokens'
        ) THEN
            ALTER TABLE verification_tokens
            ADD CONSTRAINT fk_verification_tokens_user_id
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
        END IF;
    END IF;
END $$;

-- Add foreign keys to core tables (user_roles, user_profiles, user_custom_attributes)
-- These already have proper foreign keys in V1, but ensure they have CASCADE

DO $$
BEGIN
    -- user_roles
    IF EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_name = 'user_roles_user_id_fkey'
        AND table_name = 'user_roles'
    ) THEN
        ALTER TABLE user_roles DROP CONSTRAINT user_roles_user_id_fkey;
    END IF;

    ALTER TABLE user_roles
    ADD CONSTRAINT user_roles_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

    -- user_profiles
    IF EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_name = 'user_profiles_user_id_fkey'
        AND table_name = 'user_profiles'
    ) THEN
        ALTER TABLE user_profiles DROP CONSTRAINT user_profiles_user_id_fkey;
    END IF;

    ALTER TABLE user_profiles
    ADD CONSTRAINT user_profiles_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

    -- user_custom_attributes
    IF EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_name = 'user_custom_attributes_user_id_fkey'
        AND table_name = 'user_custom_attributes'
    ) THEN
        ALTER TABLE user_custom_attributes DROP CONSTRAINT user_custom_attributes_user_id_fkey;
    END IF;

    ALTER TABLE user_custom_attributes
    ADD CONSTRAINT user_custom_attributes_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

    -- tokens
    IF EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_name = 'tokens_user_id_fkey'
        AND table_name = 'tokens'
    ) THEN
        ALTER TABLE tokens DROP CONSTRAINT tokens_user_id_fkey;
    END IF;

    ALTER TABLE tokens
    ADD CONSTRAINT tokens_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
END $$;
