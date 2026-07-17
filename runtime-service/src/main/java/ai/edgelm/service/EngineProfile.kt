package ai.edgelm.service

import android.content.Context

/**
 * Persisted per-DEVICE engine verdicts, learned by probing (docs/ENGINE-ABSTRACTION.md, Phase C).
 *
 * Today it records whether LiteRT-LM's GPU backend actually initializes on this device — many
 * Mali/Exynos GPUs can't build the delegate (the GPU init throws), and LiteRT is only worth using
 * on GPU. Once we've learned "GPU unusable," [EngineRegistry] stops selecting LiteRT here, so the
 * device self-corrects to llama.cpp instead of re-downloading and re-failing a model it can't
 * accelerate. This is the runtime-measured selection policy from the architecture doc.
 */
object EngineProfile {
    private const val FILE = "edgelm_engine_profile"
    private const val KEY_LITERT_GPU = "litert_gpu"   // "1" = works, "0" = fails, absent = unknown

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** true = GPU works, false = GPU unusable, null = not yet probed on this device. */
    fun litertGpuUsable(ctx: Context): Boolean? =
        sp(ctx).getString(KEY_LITERT_GPU, null)?.let { it == "1" }

    fun setLitertGpuUsable(ctx: Context, usable: Boolean) =
        sp(ctx).edit().putString(KEY_LITERT_GPU, if (usable) "1" else "0").apply()
}
