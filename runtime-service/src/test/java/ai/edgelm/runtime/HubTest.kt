package ai.edgelm.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for Hub's Context-free logic (arch doc Part 10): content-address
 * verification and family resolution. No Android framework or device needed — these
 * don't touch pin/rollback (which use Context.filesDir) or resolve(ctx,…).
 *
 * Run: ./gradlew :runtime-service:testDebugUnitTest
 */
class HubTest {

    // A real GGUF catalog entry to derive test specs from (sha256 defaults to null).
    private val base: ModelSpec = ModelCatalog.models.first { it.format == "gguf" }

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("edgelm-hub-test", ".bin").apply { writeBytes(bytes); deleteOnExit() }

    // ---- content-addressed integrity -----------------------------------------

    @Test fun `sha256 of empty file matches the known vector`() {
        val f = tempFile(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hub.sha256Of(f)
        )
    }

    @Test fun `verify returns Ok when the hash matches`() {
        val f = tempFile("edgelm".toByteArray())
        val spec = base.copy(sha256 = Hub.sha256Of(f))
        assertEquals(Hub.Verification.Ok, Hub.verify(f, spec, "gguf"))
    }

    @Test fun `verify rejects a tampered file as Mismatch`() {
        val f = tempFile("edgelm".toByteArray())
        val spec = base.copy(sha256 = "0".repeat(64))   // deliberately wrong
        val result = Hub.verify(f, spec, "gguf")
        assertTrue(result is Hub.Verification.Mismatch)
        assertEquals("0".repeat(64), (result as Hub.Verification.Mismatch).expected)
    }

    @Test fun `verify is Unverified when the catalog has no hash`() {
        val f = tempFile("edgelm".toByteArray())
        assertEquals(Hub.Verification.Unverified, Hub.verify(f, base, "gguf"))   // base.sha256 == null
    }

    @Test fun `verify uses the litert hash for the litertlm format`() {
        val f = tempFile("gpu".toByteArray())
        val spec = base.copy(sha256 = "0".repeat(64), litertSha256 = Hub.sha256Of(f))
        assertEquals(Hub.Verification.Ok, Hub.verify(f, spec, "litertlm"))       // picks litertSha256
    }

    // ---- family resolution ----------------------------------------------------

    @Test fun `familyOf tiers by min-RAM when unset`() {
        assertEquals("llm.tiny", Hub.familyOf(base.copy(family = null, minRamMb = 1024)))
        assertEquals("llm.small", Hub.familyOf(base.copy(family = null, minRamMb = 2048)))
        assertEquals("llm.medium", Hub.familyOf(base.copy(family = null, minRamMb = 4096)))
    }

    @Test fun `explicit family overrides the size heuristic`() {
        assertEquals("custom", Hub.familyOf(base.copy(family = "custom", minRamMb = 4096)))
    }

    @Test fun `bestInFamily returns the largest fitting model`() {
        // With generous RAM, the small tier resolves to its largest member.
        val small = Hub.bestInFamily("llm.small", ramMb = 8192)
        assertTrue(small != null && Hub.familyOf(small) == "llm.small")
        // With too little RAM, nothing in the tier fits.
        assertEquals(null, Hub.bestInFamily("llm.medium", ramMb = 1024))
    }
}
