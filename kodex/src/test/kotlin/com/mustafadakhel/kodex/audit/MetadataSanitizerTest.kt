package com.mustafadakhel.kodex.audit

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class MetadataSanitizerTest : DescribeSpec({

    describe("MetadataSanitizer") {

        describe("HTML escaping") {

            it("should escape basic HTML entities") {
                val input = mapOf(
                    "script" to "<script>alert('XSS')</script>",
                    "tag" to "<img src=x onerror=alert('XSS')>",
                    "attribute" to "value=\"injected\""
                )

                val result = MetadataSanitizer.sanitize(input)

                result shouldContainExactly mapOf(
                    "script" to "&lt;script&gt;alert(&#x27;XSS&#x27;)&lt;&#x2F;script&gt;",
                    "tag" to "&lt;img src=x onerror=alert(&#x27;XSS&#x27;)&gt;",
                    "attribute" to "value=&quot;injected&quot;"
                )
            }

            it("should escape ampersands") {
                val input = mapOf("text" to "Bob & Alice")
                val result = MetadataSanitizer.sanitize(input)
                result["text"] shouldBe "Bob &amp; Alice"
            }

            it("should escape less than and greater than signs") {
                val input = mapOf("comparison" to "5 < 10 > 3")
                val result = MetadataSanitizer.sanitize(input)
                result["comparison"] shouldBe "5 &lt; 10 &gt; 3"
            }

            it("should escape quotes") {
                val input = mapOf(
                    "double" to "He said \"hello\"",
                    "single" to "It's a test"
                )
                val result = MetadataSanitizer.sanitize(input)
                result["double"] shouldBe "He said &quot;hello&quot;"
                result["single"] shouldBe "It&#x27;s a test"
            }

            it("should escape forward slashes") {
                val input = mapOf("url" to "http://example.com/path")
                val result = MetadataSanitizer.sanitize(input)
                result["url"] shouldBe "http:&#x2F;&#x2F;example.com&#x2F;path"
            }

            it("should handle multiple XSS attack vectors") {
                val input = mapOf(
                    "onload" to "<body onload=alert('XSS')>",
                    "javascript" to "javascript:alert('XSS')",
                    "img" to "<img src=\"x\" onerror=\"alert('XSS')\">",
                    "iframe" to "<iframe src=\"javascript:alert('XSS')\"></iframe>"
                )

                val result = MetadataSanitizer.sanitize(input)

                result["onload"] shouldBe "&lt;body onload=alert(&#x27;XSS&#x27;)&gt;"
                result["javascript"] shouldBe "javascript:alert(&#x27;XSS&#x27;)"
                result["img"] shouldBe "&lt;img src=&quot;x&quot; onerror=&quot;alert(&#x27;XSS&#x27;)&quot;&gt;"
                result["iframe"] shouldBe "&lt;iframe src=&quot;javascript:alert(&#x27;XSS&#x27;)&quot;&gt;&lt;&#x2F;iframe&gt;"
            }
        }

        describe("Sensitive field redaction") {

            it("should redact password fields") {
                val input = mapOf(
                    "username" to "alice",
                    "password" to "secret123",
                    "email" to "alice@example.com"
                )

                val result = MetadataSanitizer.sanitize(input)

                result shouldContainExactly mapOf(
                    "username" to "alice",
                    "password" to "[REDACTED]",
                    "email" to "alice@example.com"
                )
            }

            it("should redact all password variants") {
                val input = mapOf(
                    "password" to "secret",
                    "newPassword" to "newsecret",
                    "oldPassword" to "oldsecret",
                    "currentPassword" to "current"
                )

                val result = MetadataSanitizer.sanitize(input)

                result.values.all { it == "[REDACTED]" } shouldBe true
            }

            it("should redact token fields") {
                val input = mapOf(
                    "accessToken" to "eyJhbGciOiJIUzI1NiIs...",
                    "refreshToken" to "refresh_token_value",
                    "token" to "generic_token"
                )

                val result = MetadataSanitizer.sanitize(input)

                result.values.all { it == "[REDACTED]" } shouldBe true
            }

            it("should redact sensitive fields with case-insensitive matching") {
                val input = mapOf(
                    "PASSWORD" to "secret",
                    "AccessToken" to "token",
                    "api_key" to "key123"
                )

                val result = MetadataSanitizer.sanitize(input)

                result.values.all { it == "[REDACTED]" } shouldBe true
            }

            it("should redact fields containing sensitive keywords") {
                val input = mapOf(
                    "userPassword" to "secret",
                    "authToken" to "token",
                    "privateKeyData" to "key"
                )

                val result = MetadataSanitizer.sanitize(input)

                result.values.all { it == "[REDACTED]" } shouldBe true
            }

            it("should redact multiple sensitive field types") {
                val input = mapOf(
                    "username" to "alice",
                    "password" to "pass123",
                    "accessToken" to "token123",
                    "secret" to "secret123",
                    "apiKey" to "key123",
                    "otp" to "123456",
                    "email" to "alice@example.com"
                )

                val result = MetadataSanitizer.sanitize(input)

                result["username"] shouldBe "alice"
                result["email"] shouldBe "alice@example.com"
                result["password"] shouldBe "[REDACTED]"
                result["accessToken"] shouldBe "[REDACTED]"
                result["secret"] shouldBe "[REDACTED]"
                result["apiKey"] shouldBe "[REDACTED]"
                result["otp"] shouldBe "[REDACTED]"
            }
        }

        describe("Non-string value handling") {

            it("should preserve numbers") {
                val input = mapOf(
                    "count" to 42,
                    "price" to 19.99
                )

                val result = MetadataSanitizer.sanitize(input)

                result shouldContainExactly input
            }

            it("should preserve booleans") {
                val input = mapOf(
                    "success" to true,
                    "error" to false
                )

                val result = MetadataSanitizer.sanitize(input)

                result shouldContainExactly input
            }

            it("should convert null to empty string") {
                val input: Map<String, Any?> = mapOf("field" to null)

                val result = MetadataSanitizer.sanitize(input as Map<String, Any>)

                result["field"] shouldBe ""
            }
        }

        describe("Nested structures") {

            it("should sanitize nested maps") {
                val input = mapOf(
                    "user" to mapOf(
                        "name" to "<script>alert('XSS')</script>",
                        "password" to "secret123"
                    )
                )

                val result = MetadataSanitizer.sanitize(input)

                @Suppress("UNCHECKED_CAST")
                val nestedResult = result["user"] as Map<String, Any>
                nestedResult["name"] shouldBe "&lt;script&gt;alert(&#x27;XSS&#x27;)&lt;&#x2F;script&gt;"
                nestedResult["password"] shouldBe "[REDACTED]"
            }

            it("should sanitize lists") {
                val input = mapOf(
                    "items" to listOf(
                        "<script>alert('XSS')</script>",
                        "normal text",
                        "<img src=x>"
                    )
                )

                val result = MetadataSanitizer.sanitize(input)

                @Suppress("UNCHECKED_CAST")
                val listResult = result["items"] as List<String>
                listResult[0] shouldBe "&lt;script&gt;alert(&#x27;XSS&#x27;)&lt;&#x2F;script&gt;"
                listResult[1] shouldBe "normal text"
                listResult[2] shouldBe "&lt;img src=x&gt;"
            }
        }

        describe("Real-world scenarios") {

            it("should sanitize login audit metadata") {
                val input = mapOf(
                    "identifier" to "alice@example.com",
                    "method" to "email",
                    "userAgent" to "Mozilla/5.0 <script>alert('XSS')</script>",
                    "password" to "secret123"
                )

                val result = MetadataSanitizer.sanitize(input)

                result["identifier"] shouldBe "alice@example.com"
                result["method"] shouldBe "email"
                result["userAgent"] shouldBe "Mozilla&#x2F;5.0 &lt;script&gt;alert(&#x27;XSS&#x27;)&lt;&#x2F;script&gt;"
                result["password"] shouldBe "[REDACTED]"
            }

            it("should sanitize user profile update metadata") {
                val input = mapOf(
                    "firstName" to "Alice<script>alert('XSS')</script>",
                    "lastName" to "Smith",
                    "address" to "123 Main St. <img src=x onerror=alert('XSS')>",
                    "email" to "alice@example.com"
                )

                val result = MetadataSanitizer.sanitize(input)

                result["firstName"] shouldBe "Alice&lt;script&gt;alert(&#x27;XSS&#x27;)&lt;&#x2F;script&gt;"
                result["lastName"] shouldBe "Smith"
                result["address"] shouldBe "123 Main St. &lt;img src=x onerror=alert(&#x27;XSS&#x27;)&gt;"
                result["email"] shouldBe "alice@example.com"
            }
        }
    }
})
