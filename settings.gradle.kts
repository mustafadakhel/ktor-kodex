rootProject.name = "ktor-kodex"

include(
    "sample",
    "kodex",
    "kodex-tokens",
    "kodex-ratelimit-inmemory",
    "kodex-ratelimit-redis",
    "kodex-validation",
    "kodex-lockout",
    "kodex-audit",
    "kodex-metrics",
    "kodex-verification",
    "kodex-password-reset",
    "kodex-mfa",
)
