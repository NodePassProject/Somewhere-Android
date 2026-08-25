// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin and C halves of the JNI bridge agree.
 *
 * Nothing else checks this. `external fun nativeTcpWrite(…)` compiles whether
 * or not a matching C symbol exists; the C side looks its callbacks up by name
 * *and signature string* at run time and merely logs when one is missing. So a
 * rename, a package move, or a changed parameter type produces a build that is
 * green everywhere and dies on the first packet — which on a VPN client is the
 * moment the user taps connect.
 *
 * Two contracts are checked, because they fail differently:
 *
 *  - **Kotlin → C.** Every `external fun` needs a `Java_…` definition.
 *    Missing one is `UnsatisfiedLinkError` at the call.
 *  - **C → Kotlin.** Every `GetStaticMethodID` in `nativeInit` needs a
 *    `@JvmStatic fun` of exactly that name and JNI signature. Missing one is
 *    silent: `nativeInit` logs, returns, and the stack runs with a callback
 *    that is never invoked.
 */
class NativeBridgeSymbolTest {
    private companion object {
        const val PREFIX = "Java_eu_nodepass_somewhere_vpn_NativeBridge_"
        val KOTLIN = File("src/main/kotlin/eu/nodepass/somewhere/vpn/NativeBridge.kt")
        val C = File("src/main/jni/lwip_jni_bridge.c")

        /** Kotlin types as JNI signature letters. Anything absent is a test failure. */
        val JNI =
            mapOf(
                "ByteArray" to "[B",
                "ByteArray?" to "[B",
                "Int" to "I",
                "Long" to "J",
                "Boolean" to "Z",
                "String" to "Ljava/lang/String;",
                "String?" to "Ljava/lang/String;",
                "Unit" to "V",
            )
    }

    private fun kotlinSource(): String {
        assertTrue("${KOTLIN.absolutePath} not found; the scan path is wrong", KOTLIN.isFile)
        return KOTLIN.readText()
    }

    private fun cSource(): String {
        assertTrue("${C.absolutePath} not found; the scan path is wrong", C.isFile)
        return C.readText()
    }

    private fun cDefinitions(): Set<String> =
        Regex("""JNIEXPORT\s+\w+\s+JNICALL\s+$PREFIX(\w+)\s*\(""")
            .findAll(cSource())
            .map { it.groupValues[1] }
            .toSet()

    private fun externalFunctionNames(): Set<String> =
        Regex("""external\s+fun\s+(\w+)\s*\(""")
            .findAll(kotlinSource())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun neitherSideIsEmptySoNothingPassesVacuously() {
        assertTrue("no external fun found in NativeBridge.kt", externalFunctionNames().size >= 10)
        assertTrue("no Java_ definitions found in the JNI bridge", cDefinitions().size >= 10)
    }

    @Test
    fun everyExternalFunHasACDefinition() {
        val missing = externalFunctionNames() - cDefinitions()
        assertTrue(
            "declared in Kotlin with no C symbol — these throw UnsatisfiedLinkError when called: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun everyCDefinitionHasAnExternalFun() {
        val orphans = cDefinitions() - externalFunctionNames()
        assertTrue(
            "exported by the native library and reachable from nothing: $orphans. Either the " +
                "Kotlin declaration was removed and the C should follow, or it was renamed and " +
                "the old symbol is now dead weight in every APK.",
            orphans.isEmpty(),
        )
    }

    @Test
    fun everyCallbackTheCSideLooksUpExistsWithThatExactSignature() {
        // The C side resolves these by name *and* signature. A Kotlin parameter
        // changed from Int to Long leaves the name matching and the lookup
        // failing, and the only symptom is a callback that never fires.
        val looked =
            Regex("""GetStaticMethodID\s*\([^,]+,\s*[^,]+,\s*\n?\s*"(\w+)",\s*"([^"]+)"\)""")
                .findAll(cSource())
                .associate { it.groupValues[1] to it.groupValues[2] }

        assertTrue("no GetStaticMethodID calls found; the C scan is wrong", looked.size >= 6)

        val source = kotlinSource()
        looked.forEach { (name, expected) ->
            val declaration =
                Regex("""@JvmStatic\s+fun\s+$name\s*\(([^)]*)\)\s*(?::\s*([\w?]+))?""", RegexOption.DOT_MATCHES_ALL)
                    .find(source)
            assertTrue("the C side looks up `$name` and Kotlin declares no @JvmStatic fun of that name", declaration != null)

            val params =
                declaration!!
                    .groupValues[1]
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { it.substringAfter(":").trim() }
            val returns = declaration.groupValues[2].ifEmpty { "Unit" }

            val actual =
                buildString {
                    append('(')
                    params.forEach { type ->
                        append(JNI[type] ?: error("$name: no JNI mapping for Kotlin type `$type`; add it to this test"))
                    }
                    append(')')
                    append(JNI[returns] ?: error("$name: no JNI mapping for return type `$returns`"))
                }

            assertEquals(
                "`$name` is looked up by the C side as `$expected` but Kotlin declares `$actual`. " +
                    "The lookup fails at nativeInit, is only logged, and the callback then never fires.",
                expected,
                actual,
            )
        }
    }
}
