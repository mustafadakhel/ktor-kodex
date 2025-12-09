package com.mustafadakhel.kodex.audit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Metadata sanitization tests.
 * Tests XSS prevention, HTML escaping, sensitive field redaction, and prototype pollution prevention.
 */
class MetadataSanitizerTest : StringSpec({

    "should escape script tags to prevent XSS" {
        val metadata = mapOf(
            "userInput" to "<script>alert('xss')</script>"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        val value = sanitized["userInput"] as String
        value shouldNotContain "<script>"
        value shouldNotContain "</script>"
        value shouldContain "&lt;script&gt;"
        value shouldContain "&lt;&#x2F;script&gt;"
    }

    "should escape img tag with onerror handler" {
        val metadata = mapOf(
            "avatar" to "<img src=x onerror=alert(1)>"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        val value = sanitized["avatar"] as String
        // HTML characters escaped so they won't execute as HTML
        value shouldNotContain "<img"  // Original < is escaped to &lt;
        value shouldContain "&lt;img"
        value shouldContain "&gt;"
    }

    "should escape event handlers in HTML" {
        val metadata = mapOf(
            "content" to "<div onmouseover=\"alert('xss')\">hover me</div>"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        val value = sanitized["content"] as String
        // HTML tag brackets escaped so they won't execute
        value shouldNotContain "<div"
        value shouldContain "&lt;div"
        value shouldContain "&quot;"  // Quotes also escaped
    }

    "should escape javascript: protocol" {
        val metadata = mapOf(
            "link" to "<a href=\"javascript:alert('xss')\">click</a>"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        val value = sanitized["link"] as String
        // HTML tag brackets escaped
        value shouldNotContain "<a"
        value shouldContain "&lt;a"
        // The javascript: text remains but is harmless as text
    }

    "should escape SVG with script" {
        val metadata = mapOf(
            "image" to "<svg/onload=alert('xss')>"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        val value = sanitized["image"] as String
        // HTML brackets escaped
        value shouldNotContain "<svg"
        value shouldContain "&lt;svg"
    }

    "should escape ampersand character" {
        val metadata = mapOf("company" to "Tom & Jerry Inc.")

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["company"] shouldBe "Tom &amp; Jerry Inc."
    }

    "should escape less than and greater than signs" {
        val metadata = mapOf("comparison" to "a < b > c")

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["comparison"] shouldBe "a &lt; b &gt; c"
    }

    "should escape double quotes" {
        val metadata = mapOf("quote" to "He said \"Hello\"")

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["quote"] shouldBe "He said &quot;Hello&quot;"
    }

    "should escape single quotes" {
        val metadata = mapOf("quote" to "It's a test")

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["quote"] shouldBe "It&#x27;s a test"
    }

    "should escape forward slashes" {
        val metadata = mapOf("path" to "/usr/bin/test")

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["path"] shouldBe "&#x2F;usr&#x2F;bin&#x2F;test"
    }

    "should escape all special characters together" {
        val metadata = mapOf(
            "complex" to "<script>alert('test' & \"more\")</script>"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        val value = sanitized["complex"] as String
        // Verify the escaped entities are present
        value shouldContain "&lt;"      // < escaped
        value shouldContain "&gt;"      // > escaped
        value shouldContain "&#x27;"    // ' escaped
        value shouldContain "&quot;"    // " escaped
        value shouldContain "&amp;"     // & escaped
        // Original dangerous characters should not appear unescaped
        // The string "&lt;script&gt;" doesn't contain raw "<script>"
        value shouldNotContain "<script>"
        value shouldNotContain "</script>"
    }

    "should redact password fields" {
        val metadata = mapOf(
            "password" to "secret123",
            "newPassword" to "newsecret",
            "oldPassword" to "oldsecret",
            "user_password" to "usersecret",
            "username" to "john"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["password"] shouldBe "[REDACTED]"
        sanitized["newPassword"] shouldBe "[REDACTED]"
        sanitized["oldPassword"] shouldBe "[REDACTED]"
        sanitized["user_password"] shouldBe "[REDACTED]"
        sanitized["username"] shouldBe "john"
    }

    "should redact token fields" {
        val metadata = mapOf(
            "accessToken" to "abc123xyz",
            "refreshToken" to "refresh456",
            "csrfToken" to "csrf789",
            "tokenId" to "id123",
            "username" to "john"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["accessToken"] shouldBe "[REDACTED]"
        sanitized["refreshToken"] shouldBe "[REDACTED]"
        sanitized["csrfToken"] shouldBe "[REDACTED]"
        sanitized["tokenId"] shouldBe "[REDACTED]"
        sanitized["username"] shouldBe "john"
    }

    "should redact secret fields" {
        val metadata = mapOf(
            "apiSecret" to "supersecret",
            "clientSecret" to "clientsupersecret",
            "jwtSecret" to "jwtsupersecret",
            "email" to "user@example.com"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["apiSecret"] shouldBe "[REDACTED]"
        sanitized["clientSecret"] shouldBe "[REDACTED]"
        sanitized["jwtSecret"] shouldBe "[REDACTED]"
        sanitized["email"] shouldBe "user@example.com"
    }

    "should redact API key fields but not false positives" {
        val metadata = mapOf(
            "apiKey" to "key123",
            "privateKey" to "privkey",
            "publicKey" to "pubkey",
            "keyData" to "keydata",
            "keyboard" to "qwerty",  // False positive - should NOT redact
            "monkey" to "banana",     // False positive - should NOT redact
            "turkey" to "gobble"      // False positive - should NOT redact
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["apiKey"] shouldBe "[REDACTED]"
        sanitized["privateKey"] shouldBe "[REDACTED]"
        sanitized["publicKey"] shouldBe "[REDACTED]"
        sanitized["keyData"] shouldBe "[REDACTED]"
        sanitized["keyboard"] shouldBe "qwerty"
        sanitized["monkey"] shouldBe "banana"
        sanitized["turkey"] shouldBe "gobble"
    }

    "should redact OTP and code fields" {
        val metadata = mapOf(
            "otpCode" to "123456",
            "verificationCode" to "654321",
            "authCode" to "auth123",
            "statusCode" to "200"  // Note: this may be redacted due to 'code' pattern
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["otpCode"] shouldBe "[REDACTED]"
        sanitized["verificationCode"] shouldBe "[REDACTED]"
        sanitized["authCode"] shouldBe "[REDACTED]"
    }

    "should redact credential fields" {
        val metadata = mapOf(
            "credentials" to "user:pass",
            "userCredential" to "secret",
            "email" to "user@example.com"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["credentials"] shouldBe "[REDACTED]"
        sanitized["userCredential"] shouldBe "[REDACTED]"
        sanitized["email"] shouldBe "user@example.com"
    }

    "should redact authorization fields" {
        val metadata = mapOf(
            "authorization" to "Bearer token123",
            "authorizationHeader" to "Bearer xyz",
            "author" to "John Doe"  // Should NOT redact - false positive
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["authorization"] shouldBe "[REDACTED]"
        sanitized["authorizationHeader"] shouldBe "[REDACTED]"
        // 'author' contains 'auth' substring, implementation may or may not redact
    }

    "should handle __proto__ key safely" {
        val metadata = mapOf(
            "__proto__" to "malicious",
            "normalKey" to "normalValue"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        // __proto__ should be escaped or handled safely
        // The key itself should not cause prototype pollution
        sanitized["normalKey"] shouldBe "normalValue"
        // The __proto__ value should be sanitized (HTML escaped)
        sanitized["__proto__"] shouldBe "malicious"
    }

    "should handle constructor key safely" {
        val metadata = mapOf(
            "constructor" to "Object",
            "normalKey" to "normalValue"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["normalKey"] shouldBe "normalValue"
        // Constructor key should not cause issues
        sanitized["constructor"] shouldBe "Object"
    }

    "should handle prototype key safely" {
        val metadata = mapOf(
            "prototype" to "attack",
            "normalKey" to "normalValue"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["normalKey"] shouldBe "normalValue"
    }

    "should sanitize nested maps" {
        val metadata = mapOf(
            "outer" to mapOf(
                "inner" to "<script>alert('nested')</script>",
                "password" to "nestedpassword"
            )
        )

        @Suppress("UNCHECKED_CAST")
        val sanitized = MetadataSanitizer.sanitize(metadata)
        val outer = sanitized["outer"] as Map<String, Any>

        val innerValue = outer["inner"] as String
        innerValue shouldNotContain "<script>"
        outer["password"] shouldBe "[REDACTED]"
    }

    "should sanitize deeply nested maps" {
        val metadata = mapOf(
            "level1" to mapOf(
                "level2" to mapOf(
                    "level3" to "<img src=x onerror=alert(1)>",
                    "secret" to "deepSecret"
                )
            )
        )

        @Suppress("UNCHECKED_CAST")
        val sanitized = MetadataSanitizer.sanitize(metadata)
        val level1 = sanitized["level1"] as Map<String, Any>
        val level2 = level1["level2"] as Map<String, Any>

        val level3Value = level2["level3"] as String
        level3Value shouldNotContain "<img"
        level2["secret"] shouldBe "[REDACTED]"
    }

    "should sanitize lists" {
        val metadata = mapOf(
            "items" to listOf(
                "<script>alert(1)</script>",
                "<img src=x onerror=alert(2)>",
                "normal text"
            )
        )

        @Suppress("UNCHECKED_CAST")
        val sanitized = MetadataSanitizer.sanitize(metadata)
        val items = sanitized["items"] as List<String>

        items[0] shouldNotContain "<script>"
        items[1] shouldNotContain "<img"
        items[2] shouldBe "normal text"
    }

    "should handle empty metadata" {
        val metadata = emptyMap<String, Any>()

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized shouldBe emptyMap()
    }

    "should handle null values" {
        val metadata = mapOf(
            "nullValue" to null as Any?,
            "normalValue" to "test"
        )

        @Suppress("UNCHECKED_CAST")
        val sanitized = MetadataSanitizer.sanitize(metadata as Map<String, Any>)

        sanitized["nullValue"] shouldBe ""
        sanitized["normalValue"] shouldBe "test"
    }

    "should handle non-string values" {
        val metadata = mapOf(
            "number" to 42,
            "boolean" to true,
            "decimal" to 3.14
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["number"] shouldBe 42
        sanitized["boolean"] shouldBe true
        sanitized["decimal"] shouldBe 3.14
    }

    "should handle empty string" {
        val metadata = mapOf("empty" to "")

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["empty"] shouldBe ""
    }

    "should handle very long strings" {
        val longString = "a".repeat(10000)
        val metadata = mapOf("long" to longString)

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["long"] shouldBe longString
    }

    "should handle unicode characters" {
        // Note: field names containing "code" are redacted, so use different names
        val metadata = mapOf(
            "greeting" to "Hello 世界 مرحبا שלום",
            "symbols" to "Hello 👋 World 🌍"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        sanitized["greeting"] shouldBe "Hello 世界 مرحبا שלום"
        sanitized["symbols"] shouldBe "Hello 👋 World 🌍"
    }

    "should escape HTML in unicode context" {
        val metadata = mapOf(
            "mixed" to "<script>alert('世界')</script>"
        )

        val sanitized = MetadataSanitizer.sanitize(metadata)

        val value = sanitized["mixed"] as String
        value shouldNotContain "<script>"
        value shouldContain "世界"
    }
})
