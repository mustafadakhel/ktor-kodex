package com.mustafadakhel.kodex.routes.auth

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.extension.RealmExtension
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.schema.KodexDatabase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private class TestExtensionConfig : ExtensionConfig() {
    var value: String = "default"

    override fun build(context: ExtensionContext, db: KodexDatabase): RealmExtension {
        return object : RealmExtension {}
    }
}

private class AnotherExtensionConfig : ExtensionConfig() {
    override fun build(context: ExtensionContext, db: KodexDatabase): RealmExtension {
        return object : RealmExtension {}
    }
}

class RealmConfigScopeTest : DescribeSpec({

    describe("extension registration") {

        it("should register a single extension without error") {
            val scope = RealmConfigScope(Realm("test-realm"))
            scope.extension(TestExtensionConfig()) {}

            scope.extensionConfigs.size shouldBe 1
        }

        it("should register different extension types without error") {
            val scope = RealmConfigScope(Realm("test-realm"))
            scope.extension(TestExtensionConfig()) {}
            scope.extension(AnotherExtensionConfig()) {}

            scope.extensionConfigs.size shouldBe 2
        }
    }
})
