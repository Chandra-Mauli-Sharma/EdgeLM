# Model optimization — findings & roadmap

_On-device inference speed/quality for EdgeLM. CPU-only release (July 2026)._

## TL;DR

The catalog is already at the sweet spot for ARM CPU: **Q4_0 with imatrix calibration**,
which gives both the fastest ARM decode path and the best quality available at 4-bit.
There is **no config-level swap left that isn't a step backward**. The remaining gains are
model-production projects (distillation/pruning) and a future desktop MoE tier — not URL edits.

## The hard constraint

On CPU, decode is **memory-bandwidth-bound**: each token requires reading (nearly) the whole
weight tensor. So decode tok/s at a fixed quality is set by **bytes-loaded-per-token**, which is
fixed by the architecture and the bit-width. You cannot make "the 3B at 3B quality" meaningfully
faster on CPU. Every real lever is about **quality-per-byte** — making a smaller (faster) model
good enough to replace a bigger one — or about moving off CPU.

Measured baseline on the target SoC (Mali-class, 4 perf cores): 3B Q4_0 ≈ 7.5–8.5 tok/s;
0.5B ≈ 25 tok/s; >30 tok/s only at ≤0.5B.

## Why Q4_0 + imatrix is optimal for us (not a compromise)

- **imatrix is already applied.** Every catalog model uses a bartowski GGUF repo, all of which
  are "Llamacpp imatrix Quantizations" — importance-matrix calibration weights which parameters
  matter during quantization, giving better quality at the same 4-bit size. Already banked.
- **Q4_0 gets the fast ARM path for free.** Modern llama.cpp repacks a plain Q4_0 tensor into the
  i8mm/dotprod-optimized layout **at load time** (our build sets `-march=armv8.2-a+dotprod+i8mm+fp16`).
  The separate `Q4_0_4_4 / 4_8 / 8_8` "ARM-optimized" files on HF are the *old* pre-repack approach
  and are now redundant.
- **The tempting alternatives are worse for us:**
  - **IQ4_XS** (smaller, ~1.83 GB) and **Q4_K_M** (higher quality) do **not** get the Q4_0 repack
    path, and I-quants decode *slower* on CPU. You'd trade the fast ARM kernel for a prettier file
    and end up slower.
  - **Q3/IQ3** (fewer bytes → faster) lose too much quality and still miss the repack path.

Conclusion: keep Q4_0 imatrix across the catalog.

## Things tried and rejected

- **Speculative decoding (3B target + 1B draft).** Implemented and verified working
  (accept 50–60%), but a **net loss on CPU**: ~40–55% slower than plain decode, because batched
  verification of N draft tokens is ~free on a GPU but costs ~N× the matmul work on CPU, and the
  1B draft is only ~2× cheaper than the 3B here (not 3×). **Gated to GPU backends only**; the code
  stays for when Vulkan ships. See `EdgeLMService.attachDraftLocked()`.
- **KV-cache quantization.** A memory / long-context lever, not a short-context speed lever. At
  `n_ctx=1024` the KV cache is a few MB — negligible next to the ~1.9 GB of weights read per token.
  Not worth doing for speed today; revisit if context grows or RAM gets tight.

## Roadmap (real gains, real effort)

1. **Distillation / structured pruning → a custom compact EdgeLM model.** Distill a 3B's behavior
   into a ~1B student, or prune+heal, to get better quality-per-byte than any off-the-shelf small
   model. This is exactly how Llama 3.2's own 1B/3B were produced. Needs a training pipeline, data,
   and GPU hours — a project, and the most defensible long-term edge for a device runtime.
2. **MoE for a desktop/tablet tier.** A Mixture-of-Experts model loads only its *active* experts per
   token, so e.g. a 30B-A3B decodes about as fast as a 3B dense at ~8B quality. The catch is fatal
   for phones — you must still *store* all experts in RAM (~18 GB at Q4) — but it's the right answer
   where RAM is plentiful. Park for a future non-mobile EdgeLM tier.

## What NOT to do

- Don't swap the catalog to IQ4/Q4_K/Q4_0_X_X — slower on our ARM CPU.
- Don't re-enable speculative decoding on CPU expecting a speedup.
- Don't chase 3B tok/s on CPU past ~8 — it's bandwidth-bound. For "fast," steer users to the
  smaller models; for "3B fast," ship the GPU (Vulkan) backend.
