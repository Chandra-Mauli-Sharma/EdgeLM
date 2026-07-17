package ai.edgelm.service

import android.os.Build

/**
 * Capability snapshot used by [EngineRegistry] to route a model to an engine
 * (docs/ENGINE-ABSTRACTION.md, Phase C).
 *
 * Mostly generic, with one pragmatic device-specific hint: [likelyLiteRtGpuCapable]. LiteRT-LM's
 * GPU (OpenCL) backend is reliable mainly on Qualcomm **Adreno**; it commonly fails to build on
 * Mali / Exynos / MediaTek GPUs. We use that hint ONLY as a pre-download allowlist for the
 * *unknown* case — i.e. whether to even attempt LiteRT before we've learned a real verdict for this
 * device (EngineProfile). Once the device has actually been probed, the learned verdict wins.
 */
data class DeviceProfile(
    val has64BitAbi: Boolean,      // arm64 present — required by the accelerated (LiteRT) libs
    val socManufacturer: String,   // "" if unknown (< API 31)
) {
    /** Pre-download allowlist: is this SoC in the GPU family where LiteRT-GPU tends to work? */
    val likelyLiteRtGpuCapable: Boolean
        get() = socManufacturer.contains("Qualcomm", true) || socManufacturer.contains("QTI", true)

    companion object {
        fun current(): DeviceProfile {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (Build.SOC_MANUFACTURER ?: "") else ""
            return DeviceProfile(
                has64BitAbi = Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() == true,
                socManufacturer = soc,
            )
        }
    }
}
