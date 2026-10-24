package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.playback.PlaybackDiagnostics
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The no-auth-logging contract. The Phase 2 research found the
 * reference implementation logging every authenticated request —
 * session and authorization headers included — to Logcat. OffLeash's
 * rule is that auth material is never logged anywhere, and this test
 * pins it two ways: the production shared client must carry no
 * logging interceptor, and the main sources must contain no
 * header/body logging tokens at all. (The diagnostics
 * RangeInterceptor stays: it records status + host only.)
 */
class LoggingContractTest {

    @Test
    fun sharedClientHasNoLoggingInterceptor() {
        val client = buildSharedClient(PlaybackDiagnostics())
        val all = client.interceptors + client.networkInterceptors
        val offenders = all.filter { interceptor ->
            interceptor.javaClass.name.contains("logging", ignoreCase = true) ||
                interceptor.javaClass.name == "okhttp3.logging.HttpLoggingInterceptor"
        }
        assertTrue(
            "the shared client must not install a logging interceptor (found: " +
                all.map { it.javaClass.name } + ")",
            offenders.isEmpty(),
        )
    }

    @Test
    fun mainSourcesContainNoHeaderLoggingTokens() {
        val javaRoot = locateMainJavaRoot()
        val forbidden = listOf("HttpLoggingInterceptor", "Level.BODY", "Level.HEADERS")
        val offenders = mutableListOf<String>()
        javaRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                for (token in forbidden) {
                    if (text.contains(token)) {
                        offenders += "${file.path} contains $token"
                    }
                }
            }
        assertTrue(
            "no main source may log headers or bodies (offenders: $offenders)",
            offenders.isEmpty(),
        )
    }

    private fun locateMainJavaRoot(): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/java")
            candidates += File(dir, "app/src/main/java")
            dir = dir.parentFile
            hops++
        }
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "src/main/java not found from working dir " + File(".").absolutePath +
                    " (tried: " + candidates.joinToString { it.path } + ")",
            )
    }
}
