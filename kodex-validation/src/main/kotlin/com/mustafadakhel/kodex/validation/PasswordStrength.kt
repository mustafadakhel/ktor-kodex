package com.mustafadakhel.kodex.validation

import kotlin.time.Duration

public data class PasswordStrength(
    val score: Int,
    val entropy: Double,
    val crackTime: Duration,
    val feedback: List<String>,
    val isAcceptable: Boolean
) {
    public fun getQualityLevel(): PasswordQuality {
        return when (score) {
            0 -> PasswordQuality.VERY_WEAK
            1 -> PasswordQuality.WEAK
            2 -> PasswordQuality.MODERATE
            3 -> PasswordQuality.STRONG
            4 -> PasswordQuality.VERY_STRONG
            else -> PasswordQuality.VERY_WEAK
        }
    }
}

public enum class PasswordQuality {
    VERY_WEAK,
    WEAK,
    MODERATE,
    STRONG,
    VERY_STRONG
}
