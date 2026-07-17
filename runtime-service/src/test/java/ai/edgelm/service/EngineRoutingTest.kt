package ai.edgelm.service

import ai.edgelm.runtime.ModelCatalog
import ai.edgelm.runtime.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for engine routing + download-artifact selection — no Android framework or
 * native library needed (nothing here calls load/generate, so neither NativeBridge nor the
 * LiteRT AAR is touched). These pin the Phase B routing: a GGUF model runs on llama.cpp; a
 * `.litertlm` model on a 64-bit device routes to litert-lm (GPU); everything else falls back to
 * llama.cpp. (EngineRegistry is used without init(), so its LiteRtEngine has a null Context —
 * fine, because capability/supports checks don't dereference it.)
 */
class EngineRoutingTest {

    private val device64 = DeviceProfile(has64BitAbi = true, socManufacturer = "Qualcomm")
    private val device32 = DeviceProfile(has64BitAbi = false, socManufacturer = "Qualcomm")

    // A real GGUF catalog entry, and a dual-artifact variant that also ships a .litertlm.
    private val ggufSpec: ModelSpec = ModelCatalog.models.first { it.format == "gguf" }
    private val litertSpec: ModelSpec = ggufSpec.copy(litertUrl = "https://example.test/model.litertlm")

    // ---- registry routing ----------------------------------------------------

    @Test fun `gguf model routes to llama_cpp`() {
        assertEquals("llama.cpp", EngineRegistry.select(ggufSpec, device64).id)
    }

    @Test fun `litertlm model on a 64-bit device routes to litert-lm`() {
        assertEquals("litert-lm", EngineRegistry.select(litertSpec, device64).id)
    }

    @Test fun `litertlm model on a 32-bit device falls back to llama_cpp`() {
        // LiteRtEngine.canRunOn(has64=false) is false, and llama.cpp supports the gguf format, so
        // routing lands on the CPU engine.
        assertEquals("llama.cpp", EngineRegistry.select(litertSpec, device32).id)
    }

    @Test fun `null spec falls back to llama_cpp`() {
        assertEquals("llama.cpp", EngineRegistry.select(null, device64).id)
    }

    // ---- engine capability + artifact selection ------------------------------

    @Test fun `llama_cpp supports gguf and yields the gguf artifact`() {
        val e = LlamaCppEngine()
        assertTrue(e.supports(ggufSpec))
        assertTrue(e.canRunOn(device64))
        assertEquals(ModelArtifact(ggufSpec.url, "gguf"), e.artifactFor(ggufSpec))
    }

    @Test fun `litert supports litertlm models and runs only on 64-bit`() {
        val e = LiteRtEngine()
        assertTrue(e.supports(litertSpec))
        assertFalse(e.supports(ggufSpec))
        assertTrue(e.canRunOn(device64))
        assertFalse(e.canRunOn(device32))
    }

    @Test fun `litert artifact is the litertlm url, or null when absent`() {
        val e = LiteRtEngine()
        assertEquals(ModelArtifact(litertSpec.litertUrl!!, "litertlm"), e.artifactFor(litertSpec))
        assertNull(e.artifactFor(ggufSpec))
    }

    // ---- pre-download GPU allowlist ------------------------------------------

    @Test fun `allowlist flags Adreno-Qualcomm SoCs as likely GPU-capable`() {
        assertTrue(DeviceProfile(has64BitAbi = true, socManufacturer = "Qualcomm").likelyLiteRtGpuCapable)
        assertTrue(DeviceProfile(has64BitAbi = true, socManufacturer = "QTI").likelyLiteRtGpuCapable)
        assertFalse(DeviceProfile(has64BitAbi = true, socManufacturer = "ARM").likelyLiteRtGpuCapable)
        assertFalse(DeviceProfile(has64BitAbi = true, socManufacturer = "Mediatek").likelyLiteRtGpuCapable)
        assertFalse(DeviceProfile(has64BitAbi = true, socManufacturer = "").likelyLiteRtGpuCapable)
    }
}
