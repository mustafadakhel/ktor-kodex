package com.mustafadakhel.kodex.validation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Input Sanitizer Tests
 * Tests XSS prevention, HTML entity escaping, and control character removal.
 */
class InputSanitizerTest : StringSpec({

    "Script tag should be stripped from HTML context" {
        val sanitizer = InputSanitizer()
        val input = "<script>alert('xss')</script>Hello"
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldNotContain "<script>"
        result shouldNotContain "</script>"
        result shouldNotContain "alert"
        result shouldContain "Hello"
    }

    "Img tag with onerror should be stripped" {
        val sanitizer = InputSanitizer()
        val input = "<img src=x onerror=alert(1)>Safe"
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldNotContain "<img"
        result shouldNotContain "onerror"
        result shouldContain "Safe"
    }

    "Iframe tag should be stripped" {
        val sanitizer = InputSanitizer()
        val input = "<iframe src='malicious.com'></iframe>Content"
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldNotContain "<iframe"
        result shouldContain "Content"
    }

    "Object tag should be stripped" {
        val sanitizer = InputSanitizer()
        val input = "<object data='malicious.swf'></object>Text"
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldNotContain "<object"
        result shouldContain "Text"
    }

    "Event handlers in any tag should be stripped" {
        val sanitizer = InputSanitizer()
        val input = "<div onmouseover='steal()'>Hover me</div>"
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldNotContain "onmouseover"
        result shouldNotContain "steal"
    }

    "JavaScript URI should be stripped" {
        val sanitizer = InputSanitizer()
        val input = "<a href='javascript:alert(1)'>Click</a>"
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldNotContain "javascript:"
    }

    "Data URI in img src should be stripped" {
        val sanitizer = InputSanitizer()
        val input = "<img src='data:text/html,<script>alert(1)</script>'>"
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldNotContain "data:"
        result shouldNotContain "<script>"
    }

    "SVG with embedded script should be stripped" {
        val sanitizer = InputSanitizer()
        val input = "<svg><script>alert(1)</script></svg>"
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldNotContain "<svg>"
        result shouldNotContain "<script>"
    }

    "Style tag with expression should be stripped" {
        val sanitizer = InputSanitizer()
        val input = "<style>body{background:url('javascript:alert(1)')}</style>"
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldNotContain "<style>"
        result shouldNotContain "javascript:"
    }

    "Plain text should remain intact" {
        val sanitizer = InputSanitizer()
        val input = "Hello, World! This is plain text."
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldBe "Hello, World! This is plain text."
    }

    "Multiple XSS vectors should all be stripped" {
        val sanitizer = InputSanitizer()
        val input = """
            <script>bad()</script>
            <img src=x onerror=bad()>
            <iframe src='evil.com'></iframe>
            <a href='javascript:bad()'>link</a>
            Safe content here
        """.trimIndent()
        val result = sanitizer.sanitizeHtml(input, InputContext.HTML)

        result shouldNotContain "<script>"
        result shouldNotContain "<img"
        result shouldNotContain "<iframe"
        result shouldNotContain "javascript:"
        result shouldContain "Safe content here"
    }

    "Ampersand should be escaped" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("Tom & Jerry")

        result shouldBe "Tom &amp; Jerry"
    }

    "Less than should be escaped" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("5 < 10")

        result shouldBe "5 &lt; 10"
    }

    "Greater than should be escaped" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("10 > 5")

        result shouldBe "10 &gt; 5"
    }

    "Double quote should be escaped" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("He said \"hello\"")

        result shouldBe "He said &quot;hello&quot;"
    }

    "Single quote should be escaped" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("It's working")

        result shouldBe "It&#x27;s working"
    }

    "Forward slash should be escaped" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("path/to/file")

        result shouldBe "path&#x2F;to&#x2F;file"
    }

    "Multiple special characters should all be escaped" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("<script>alert('xss')</script>")

        result shouldBe "&lt;script&gt;alert(&#x27;xss&#x27;)&lt;&#x2F;script&gt;"
    }

    "Empty string should remain empty" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("")

        result shouldBe ""
    }

    "String with no special characters should remain unchanged" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("Hello World 123")

        result shouldBe "Hello World 123"
    }

    "PLAIN_TEXT context should escape HTML" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.sanitizeHtml("<b>bold</b>", InputContext.PLAIN_TEXT)

        result shouldContain "&lt;"
        result shouldContain "&gt;"
    }

    "ATTRIBUTE context should escape HTML" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.sanitizeHtml("value=\"test\"", InputContext.ATTRIBUTE)

        result shouldContain "&quot;"
    }

    "CRLF should be removed from input" {
        val sanitizer = InputSanitizer()
        val input = "Header\r\nInjection: evil"
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", input)

        value shouldNotContain "\r"
        value shouldNotContain "\n"
        // The text content should be preserved (minus control chars)
        value shouldContain "Header"
        value shouldContain "Injection"
    }

    "Carriage return alone should be removed" {
        val sanitizer = InputSanitizer()
        val input = "Line1\rLine2"
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", input)

        value shouldNotContain "\r"
    }

    "Newline alone should be removed" {
        val sanitizer = InputSanitizer()
        val input = "Line1\nLine2"
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", input)

        value shouldNotContain "\n"
    }

    "Tab character should be removed" {
        val sanitizer = InputSanitizer()
        val input = "Col1\tCol2"
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", input)

        value shouldNotContain "\t"
    }

    "Null byte should be removed" {
        val sanitizer = InputSanitizer()
        val input = "Before\u0000After"
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", input)

        value shouldNotContain "\u0000"
        value shouldContain "Before"
        value shouldContain "After"
    }

    "Bell character should be removed" {
        val sanitizer = InputSanitizer()
        val input = "Normal\u0007Text"
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", input)

        value shouldNotContain "\u0007"
    }

    "Backspace character should be removed" {
        val sanitizer = InputSanitizer()
        val input = "Normal\u0008Text"
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", input)

        value shouldNotContain "\u0008"
    }

    "Form feed should be removed" {
        val sanitizer = InputSanitizer()
        val input = "Page1\u000CPage2"
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", input)

        value shouldNotContain "\u000C"
    }

    "Vertical tab should be removed" {
        val sanitizer = InputSanitizer()
        val input = "Line1\u000BLine2"
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", input)

        value shouldNotContain "\u000B"
    }

    "Multiple control characters should all be removed" {
        val sanitizer = InputSanitizer()
        val input = "A\r\n\t\u0000B\u0007C"
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", input)

        value shouldNotContain "\r"
        value shouldNotContain "\n"
        value shouldNotContain "\t"
        value shouldNotContain "\u0000"
        value shouldNotContain "\u0007"
        value shouldContain "A"
        value shouldContain "B"
        value shouldContain "C"
    }

    "Key should be sanitized to alphanumeric, underscore, hyphen, dot" {
        val sanitizer = InputSanitizer()
        val (key, _) = sanitizer.sanitizeCustomAttribute("my_key-1.0", "value")

        key shouldBe "my_key-1.0"
    }

    "Key with invalid characters should have them removed" {
        val sanitizer = InputSanitizer()
        val (key, _) = sanitizer.sanitizeCustomAttribute("my@key#test!", "value")

        key shouldNotContain "@"
        key shouldNotContain "#"
        key shouldNotContain "!"
    }

    "Key should be trimmed" {
        val sanitizer = InputSanitizer()
        val (key, _) = sanitizer.sanitizeCustomAttribute("  mykey  ", "value")

        key shouldBe "mykey"
    }

    "Key exceeding max length should be truncated" {
        val sanitizer = InputSanitizer(maxKeyLength = 10)
        val (key, _) = sanitizer.sanitizeCustomAttribute("verylongkeyname", "value")

        key.length shouldBe 10
        key shouldBe "verylongke"
    }

    "Empty key after sanitization should throw" {
        val sanitizer = InputSanitizer()

        shouldThrow<IllegalArgumentException> {
            sanitizer.sanitizeCustomAttribute("@#$%", "value")
        }
    }

    "Value should be trimmed" {
        val sanitizer = InputSanitizer()
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", "  value  ")

        // Value might have HTML escaping, but should be trimmed
        value.startsWith(" ").shouldBe(false)
    }

    "Value exceeding max length should be truncated" {
        val sanitizer = InputSanitizer(maxValueLength = 10)
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", "a".repeat(20))

        // Value is truncated then HTML escaped
        value.length shouldBe 10
    }

    "Value should have HTML entities escaped" {
        val sanitizer = InputSanitizer()
        val (_, value) = sanitizer.sanitizeCustomAttribute("key", "<script>bad</script>")

        value shouldContain "&lt;"
        value shouldContain "&gt;"
        value shouldNotContain "<script>"
    }

    "Email should be trimmed and lowercased" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.sanitizeEmail("  USER@EXAMPLE.COM  ")

        result shouldBe "user@example.com"
    }

    "Email with mixed case should be lowercased" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.sanitizeEmail("User.Name@Example.COM")

        result shouldBe "user.name@example.com"
    }

    "Email without whitespace should remain unchanged (except case)" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.sanitizeEmail("user@example.com")

        result shouldBe "user@example.com"
    }

    "Phone with + prefix should preserve it" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.sanitizePhone("+1 234 567 8900")

        result shouldBe "+12345678900"
    }

    "Phone without + prefix should have only digits" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.sanitizePhone("(234) 567-8900")

        result shouldBe "2345678900"
    }

    "Phone with various separators should have them removed" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.sanitizePhone("234-567-8900")

        result shouldBe "2345678900"
    }

    "Phone should be trimmed" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.sanitizePhone("  +1234567890  ")

        result shouldBe "+1234567890"
    }

    "Phone with letters should have letters removed" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.sanitizePhone("+1-800-FLOWERS")

        // Letters should be filtered out, keeping only + and digits
        result shouldBe "+1800"
    }

    "Empty input should return empty for escapeHtml" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("")

        result shouldBe ""
    }

    "Unicode characters should be preserved" {
        val sanitizer = InputSanitizer()
        val result = sanitizer.escapeHtml("日本語テスト")

        result shouldBe "日本語テスト"
    }

    "Very long input should be handled" {
        val sanitizer = InputSanitizer()
        val longInput = "A".repeat(10000)
        val result = sanitizer.escapeHtml(longInput)

        result shouldBe longInput
    }
})
