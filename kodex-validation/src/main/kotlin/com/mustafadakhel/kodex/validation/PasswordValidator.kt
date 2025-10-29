package com.mustafadakhel.kodex.validation

import java.util.Locale
import kotlin.math.log2
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Validates password strength using zxcvbn-inspired entropy calculation.
 * Scores passwords 0-4 based on entropy, patterns, and dictionary attacks.
 */
internal class PasswordValidator(
    private val minLength: Int = 8,
    private val minScore: Int = 2,
    private val commonPasswords: Set<String> = CommonPasswords.top10k
) {
    private companion object {
        const val MAX_PASSWORD_LENGTH = 256  // Prevent DoS from extremely long passwords
        const val RECOMMENDED_MIN_LENGTH = 12  // Recommended length for better security
        const val MIN_SEQUENTIAL_CHARS = 3  // Minimum length for sequential pattern detection
        const val MIN_REPEATED_CHARS = 3  // Minimum consecutive repeats to flag

        // Brute force attack assumptions (10 billion guesses/second on modern hardware)
        const val GUESSES_PER_SECOND = 10_000_000_000.0

        // Entropy thresholds for password strength scoring (bits)
        // These values align with NIST SP 800-63B guidelines
        const val ENTROPY_VERY_WEAK = 28.0   // < 3.5 bits per char for 8 chars
        const val ENTROPY_WEAK = 36.0        // ~4.5 bits per char for 8 chars
        const val ENTROPY_MODERATE = 60.0    // ~7.5 bits per char for 8 chars
        const val ENTROPY_STRONG = 128.0     // ~16 bits per char for 8 chars

        // Pre-allocated sequences for pattern detection (performance optimization)
        val SEQUENTIAL_SEQUENCES = listOf(
            "abcdefghijklmnopqrstuvwxyz",
            "0123456789"
        )

        val KEYBOARD_PATTERNS = listOf(
            "qwerty", "asdf", "zxcv", "qaz", "wsx", "edc",
            "123456", "098765"
        )
    }

    private val lowercasePool = 26
    private val uppercasePool = 26
    private val digitPool = 10
    // Special characters pool: ASCII printable symbols (32 chars)
    // !"#$%&'()*+,-./:;<=>?@[\]^_`{|}~
    private val specialPool = 32

    public fun validate(password: String, field: String = "password"): ValidationResult {
        val strength = analyzeStrength(password)
        val errors = mutableListOf<ValidationError>()

        // Length check - minimum
        if (password.length < minLength) {
            errors.add(
                ValidationError.of(
                    field = field,
                    code = "password.too_short",
                    message = "Password must be at least $minLength characters",
                    "min" to minLength,
                    "actual" to password.length
                )
            )
        }

        // Length check - maximum (DoS prevention)
        if (password.length > MAX_PASSWORD_LENGTH) {
            errors.add(
                ValidationError.of(
                    field = field,
                    code = "password.too_long",
                    message = "Password must not exceed $MAX_PASSWORD_LENGTH characters",
                    "max" to MAX_PASSWORD_LENGTH,
                    "actual" to password.length
                )
            )
        }

        // Score check
        if (strength.score < minScore) {
            errors.add(
                ValidationError.of(
                    field = field,
                    code = "password.weak",
                    message = "Password is too weak (score ${strength.score}/$minScore)",
                    "score" to strength.score,
                    "required" to minScore,
                    "quality" to strength.getQualityLevel().toString(),
                    "feedback" to strength.feedback
                )
            )
        }

        return if (errors.isEmpty()) {
            ValidationResult.valid(originalValue = password)
        } else {
            ValidationResult.invalid(errors = errors, originalValue = password)
        }
    }

    public fun analyzeStrength(password: String): PasswordStrength {
        val feedback = mutableListOf<String>()
        var penalties = 0

        // Cache lowercase version to avoid multiple allocations (use Locale.ROOT for consistency)
        val passwordLower = password.lowercase(Locale.ROOT)

        // Common password check
        if (passwordLower in commonPasswords) {
            feedback.add("This password is commonly used and easily guessed")
            return PasswordStrength(
                score = 0,
                entropy = 0.0,
                crackTime = 0.seconds,
                feedback = feedback,
                isAcceptable = false
            )
        }

        // Pattern detection
        // Note: Sequential and keyboard patterns use lowercase for case-insensitive matching.
        // Repeated chars use original case because "AAA" vs "aaa" are distinct patterns.
        if (hasSequentialChars(passwordLower)) {
            feedback.add("Avoid sequential characters (abc, 123)")
            penalties++
        }

        if (hasRepeatedChars(password)) {
            feedback.add("Avoid repeated characters (aaa, 111)")
            penalties++
        }

        if (hasKeyboardPattern(passwordLower)) {
            feedback.add("Avoid keyboard patterns (qwerty, asdf)")
            penalties++
        }

        // Cap length for entropy calculation to prevent overflow
        val effectiveLength = password.length.coerceAtMost(MAX_PASSWORD_LENGTH)

        // Calculate entropy
        val poolSize = calculatePoolSize(password)
        val entropy = log2(poolSize.toDouble()) * effectiveLength

        // Calculate crack time (assuming 10 billion guesses per second)
        val possibleCombinations = poolSize.toDouble().pow(effectiveLength)
        val crackTimeSeconds = (possibleCombinations / 2) / GUESSES_PER_SECOND
        val crackTime = crackTimeSeconds.seconds

        // Calculate score (0-4) based on entropy with penalties
        val baseScore = when {
            entropy < ENTROPY_VERY_WEAK -> 0  // Very weak
            entropy < ENTROPY_WEAK -> 1       // Weak
            entropy < ENTROPY_MODERATE -> 2   // Moderate
            entropy < ENTROPY_STRONG -> 3     // Strong
            else -> 4                         // Very strong
        }

        val finalScore = (baseScore - penalties).coerceAtLeast(0)

        // Add constructive feedback
        if (password.length < RECOMMENDED_MIN_LENGTH) {
            feedback.add("Use at least $RECOMMENDED_MIN_LENGTH characters for better security")
        }

        if (!password.any { it.isUpperCase() }) {
            feedback.add("Add uppercase letters")
        }

        if (!password.any { it.isLowerCase() }) {
            feedback.add("Add lowercase letters")
        }

        if (!password.any { it.isDigit() }) {
            feedback.add("Add numbers")
        }

        if (!password.any { !it.isLetterOrDigit() }) {
            feedback.add("Add special characters (!@#\$%)")
        }

        return PasswordStrength(
            score = finalScore,
            entropy = entropy,
            crackTime = crackTime,
            feedback = feedback,
            isAcceptable = finalScore >= minScore
        )
    }

    private fun calculatePoolSize(password: String): Int {
        var poolSize = 0
        if (password.any { it.isLowerCase() }) poolSize += lowercasePool
        if (password.any { it.isUpperCase() }) poolSize += uppercasePool
        if (password.any { it.isDigit() }) poolSize += digitPool
        if (password.any { !it.isLetterOrDigit() }) poolSize += specialPool
        return poolSize
    }

    private fun hasSequentialChars(passwordLower: String): Boolean {
        for (sequence in SEQUENTIAL_SEQUENCES) {
            for (i in 0..sequence.length - MIN_SEQUENTIAL_CHARS) {
                val subseq = sequence.substring(i, i + MIN_SEQUENTIAL_CHARS)
                if (passwordLower.contains(subseq) || passwordLower.contains(subseq.reversed())) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasRepeatedChars(password: String): Boolean {
        for (i in 0 until password.length - (MIN_REPEATED_CHARS - 1)) {
            // Check if MIN_REPEATED_CHARS consecutive characters are the same
            val allSame = (1 until MIN_REPEATED_CHARS).all { offset ->
                password[i] == password[i + offset]
            }
            if (allSame) {
                return true
            }
        }
        return false
    }

    private fun hasKeyboardPattern(passwordLower: String): Boolean {
        return KEYBOARD_PATTERNS.any { passwordLower.contains(it) }
    }
}

/**
 * Common passwords compiled from breach data.
 * Currently contains ~120 of the most frequently used passwords.
 * Used for dictionary attack detection to reject trivially weak passwords.
 *
 * For production systems requiring stricter security, consider extending
 * this set with a larger dictionary (e.g., top 10k from SecLists).
 */
internal object CommonPasswords {
    val top10k: Set<String> = setOf(
        "password", "123456", "123456789", "12345678", "12345", "1234567", "password1",
        "1234567890", "qwerty", "abc123", "111111", "123123", "1234", "password123",
        "000000", "iloveyou", "1q2w3e4r", "qwertyuiop", "123321", "monkey", "dragon",
        "654321", "666666", "sunshine", "master", "letmein", "1qaz2wsx", "qazwsx",
        "welcome", "shadow", "princess", "admin", "passw0rd", "trustno1", "login",
        "starwars", "whatever", "football", "baseball", "summer", "michael", "jordan",
        "696969", "mustang", "charlie", "jennifer", "hunter", "freedom", "michelle",
        "pussy", "harley", "ranger", "angel", "jessica", "daniel", "thomas", "test",
        "secret", "killer", "soccer", "pepper", "buster", "batman", "ashley", "access",
        "master1", "hello", "sunshine1", "computer", "tigger", "orange", "cookie",
        "amanda", "123qwe", "superman", "golfer", "hockey", "121212", "pass", "fuckme",
        "fuckyou", "trustno1", "robert", "matthew", "cowboys", "phoenix", "mypass",
        "taylor", "forever", "Joshua", "qwerty123", "rangers", "flower", "1q2w3e",
        "passw0rd", "pass123", "samsung", "12341234", "hannah", "zxcvbnm", "internet",
        "cheese", "andrew", "computer1", "chicken", "ginger", "jackson", "hammer",
        "midnight", "slayer", "purple", "silver", "maverick", "biteme", "merlin",
        "12121212", "thunder", "compaq", "internet", "whatever", "love", "scooter",
        "please", "123654", "blink182", "nicole", "madison", "killer", "7777777",
        "88888888", "pokemon", "london", "arsenal", "bailey", "welcome1", "maggie"
    )
}
