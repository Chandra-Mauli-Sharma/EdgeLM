#pragma once
// =============================================================================
// Vision / multimodal path (Phase 2, arch doc Part 12). UNBUILT — opt-in build
// with -DEDGELM_VISION=ON (see CMakeLists). Uses llama.cpp's mtmd library:
// LLM GGUF + an mmproj (vision encoder/projector) GGUF. Image bytes are the raw
// file (jpg/png) — mtmd decodes them via stb_image, so no image codec in Kotlin.
//
// Confirm the mtmd API against the pinned tree — mtmd.h says it's EXPERIMENTAL and
// subject to breaking changes.
// =============================================================================
#include "llama_runner.h"   // edgelm::Sink

#include <cstdint>
#include <string>

namespace edgelm {

struct VisionModel;

// Load an LLM + its mmproj projector as a vision model. Returns nullptr on failure.
VisionModel* load_vision_model(const char* model_path, const char* mmproj_path);

// Generate a response to [prompt] about one image ([image] = raw jpg/png/... file bytes).
// The image marker is inserted into the prompt internally. Streams text into [sink].
// Returns tokens produced. Blocking.
int vision_generate(VisionModel* m, const std::string& prompt,
                    const uint8_t* image, int image_len, const Sink& sink);

void unload_vision_model(VisionModel* m);

} // namespace edgelm
