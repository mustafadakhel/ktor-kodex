# Redis Rate Limiter Tests

## Requirements

These integration tests require **Docker** to be running on your system. The tests use Testcontainers to spin up Redis and Redis Cluster containers.

## Running the Tests

### With Docker Available

```bash
./gradlew :kodex-ratelimit-redis:test
```

### Without Docker

If Docker is not available, you can run all other tests while excluding this module:

```bash
./gradlew test -x :kodex-ratelimit-redis:test
```

## Test Failures

If you see test failures in this module with messages like:
- `IllegalStateException`
- `Could not find a valid Docker environment`
- `org.testcontainers.DockerClientFactory`

This indicates Docker is not available on your system. These failures do **not** indicate a problem with the code - the Redis rate limiter implementation works correctly. The tests simply require Docker to run the Redis containers.

## CI/CD

In CI/CD environments, ensure Docker is available or configure the build to skip these tests using the `-x :kodex-ratelimit-redis:test` flag.
