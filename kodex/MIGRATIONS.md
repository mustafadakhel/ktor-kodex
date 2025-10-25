# Database Migrations with Flyway

Kodex provides **optional** database schema migrations using [Flyway](https://flywaydb.org/) for production deployments.

**⚠️ IMPORTANT: Flyway is OPT-IN, not required**

By default, Kodex uses Exposed ORM's `SchemaUtils.create()` which automatically creates tables from your code. Most users don't need Flyway.

## Migration Modes

Kodex supports two database initialization modes:

### 1. **Auto-Schema (Default)** - Development & Testing
The default mode uses Exposed ORM's `SchemaUtils.create()` to automatically create tables on startup. This is convenient for:
- Local development
- Testing environments
- Rapid prototyping
- In-memory databases (H2)

**No action required** - tables are created automatically when Kodex starts.

### 2. **Flyway Migrations** - Production
For production environments, use Flyway migrations for:
- Version-controlled schema changes
- Reproducible deployments
- Schema rollback capabilities
- Audit trail of database changes
- Team collaboration on schema evolution

## Using Flyway Migrations

### Step 1: Configure Your Database

```kotlin
import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.util.runFlywayMigrations

fun Application.module() {
    // Install Kodex with your production database
    val kodex = install(Kodex) {
        database {
            jdbcUrl = "jdbc:postgresql://localhost:5432/production_db"
            username = System.getenv("DB_USERNAME")
            password = System.getenv("DB_PASSWORD")
            maximumPoolSize = 20
            // ... other HikariCP settings
        }

        realm("main") {
            // ... realm configuration
        }
    }

    // Run Flyway migrations before using Kodex services
    runFlywayMigrations(kodex.getDataSource())
}
```

### Step 2: Migration Files Location

Flyway migrations are located in:
```
src/main/resources/db/migration/
```

Migrations follow the naming convention:
```
V<VERSION>__<DESCRIPTION>.sql
```

Examples:
- `V1__baseline_schema.sql` - Initial schema
- `V2__add_user_preferences.sql` - Add user preferences table
- `V3__add_email_index.sql` - Add index on email column

### Step 3: Creating New Migrations

When you need to modify the schema:

1. Create a new migration file with the next version number:
   ```
   src/main/resources/db/migration/V2__your_change.sql
   ```

2. Write your DDL statements:
   ```sql
   -- Add new column to users table
   ALTER TABLE users ADD COLUMN last_password_change TIMESTAMP;

   -- Add index for performance
   CREATE INDEX idx_users_last_password_change ON users(last_password_change);
   ```

3. Test the migration locally:
   ```bash
   ./gradlew flywayMigrate
   ```

4. Commit the migration file to version control

## Baseline Migration (V1)

Kodex includes a baseline migration (`V1__baseline_schema.sql`) that creates all core tables:

- `users` - User authentication data
- `roles` - Role definitions
- `user_roles` - User-role relationships
- `user_profiles` - Extended user information
- `user_custom_attributes` - Flexible user attributes
- `tokens` - JWT tokens with rotation
- `audit_events` - Audit logging
- `failed_login_attempts` - Lockout tracking
- `account_lockouts` - Active account lockouts

### Migrating Existing Database

If you have an existing database created by Auto-Schema mode:

```kotlin
// Enable baseline on migrate to handle existing tables
runFlywayMigrations(
    dataSource = kodex.getDataSource(),
    baselineOnMigrate = true  // This is the default
)
```

Flyway will:
1. Detect existing tables
2. Create a baseline at version 1
3. Only apply migrations newer than V1

## Migration Best Practices

### 1. **Never Modify Existing Migrations**
Once a migration is applied in any environment (especially production), never modify it. Always create a new migration for changes.

❌ **Bad:**
```sql
-- Modifying V1__baseline_schema.sql after deployment
ALTER TABLE users ADD COLUMN ...
```

✅ **Good:**
```sql
-- Create V2__add_user_column.sql
ALTER TABLE users ADD COLUMN ...
```

### 2. **Make Migrations Idempotent**
Use `IF EXISTS` and `IF NOT EXISTS` to make migrations safer:

```sql
CREATE TABLE IF NOT EXISTS new_table (...);
ALTER TABLE users ADD COLUMN IF NOT EXISTS new_column VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_name ON table(column);
```

### 3. **Test Migrations Thoroughly**
Test migrations in a staging environment that mirrors production:

```bash
# Test migration
./gradlew flywayMigrate

# Verify schema
./gradlew flywayInfo
./gradlew flywayValidate

# Test rollback if supported by your database
./gradlew flywayUndo
```

### 4. **Handle Data Migrations Carefully**
For data migrations, consider:
- Using transactions
- Batching large updates
- Providing rollback scripts
- Testing with production-sized datasets

```sql
-- Example: Batch update with transaction
BEGIN;

UPDATE users
SET status = 'ACTIVE'
WHERE status IS NULL;

COMMIT;
```

### 5. **Document Breaking Changes**
Add comments to migrations that introduce breaking changes:

```sql
-- BREAKING CHANGE: Removes deprecated 'username' column
-- Action required: Applications must use 'email' for login
-- Migration guide: https://docs.example.com/migration-v2

ALTER TABLE users DROP COLUMN username;
```

## Advanced Configuration

### Custom Migration Location

```kotlin
runFlywayMigrations(
    dataSource = kodex.getDataSource(),
    locations = arrayOf(
        "classpath:db/migration",
        "filesystem:/opt/migrations"
    )
)
```

### Disable Baseline on Migrate

For fresh databases where you control the schema:

```kotlin
runFlywayMigrations(
    dataSource = kodex.getDataSource(),
    baselineOnMigrate = false
)
```

## Flyway CLI Usage

For teams preferring CLI-based migrations:

```bash
# Check migration status
./gradlew flywayInfo

# Run migrations
./gradlew flywayMigrate

# Validate migrations
./gradlew flywayValidate

# Clean database (DANGEROUS - development only)
./gradlew flywayClean
```

## Troubleshooting

### Migration Checksum Mismatch

If you see "Migration checksum mismatch":
1. **Never modify applied migrations** in production
2. For development, you can:
   ```bash
   ./gradlew flywayRepair
   ```
3. For production, create a new migration to fix the issue

### Baseline Version Conflict

If baseline version conflicts occur:
```sql
-- Manually set baseline version
DELETE FROM flyway_schema_history WHERE version = '1';
```

Then re-run migrations.

### Failed Migration

Flyway marks failed migrations. To repair:
1. Fix the migration SQL
2. Run: `./gradlew flywayRepair`
3. Re-run: `./gradlew flywayMigrate`

## Production Deployment Checklist

- [ ] Test migrations in staging environment
- [ ] Back up production database
- [ ] Review migration SQL for breaking changes
- [ ] Coordinate with dependent services
- [ ] Run `flywayValidate` before deploying
- [ ] Monitor migration execution in production
- [ ] Verify application starts successfully
- [ ] Keep rollback scripts ready (if applicable)

## Resources

- [Flyway Documentation](https://flywaydb.org/documentation/)
- [Migration Naming Convention](https://flywaydb.org/documentation/concepts/migrations#naming)
- [SQL-based Migrations](https://flywaydb.org/documentation/concepts/migrations#sql-based-migrations)
