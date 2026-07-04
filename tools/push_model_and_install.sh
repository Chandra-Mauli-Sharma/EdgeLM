#!/usr/bin/env bash
# Convenience: build + install all modules, then push a GGUF model into the
# runtime-service's private files dir so the service can mmap it.
set -euo pipefail

MODEL="${1:-}"
if [[ -z "$MODEL" || ! -f "$MODEL" ]]; then
  echo "Usage: tools/push_model_and_install.sh /path/to/model.gguf"
  echo "  (grab a small Q4 GGUF, e.g. a 1B–3B instruct model, for the spike)"
  exit 1
fi

echo "== building + installing modules =="
./gradlew :runtime-service:installDebug :demo-app-a:installDebug :demo-app-b:installDebug

echo "== pushing model into runtime-service files dir =="
# App-private storage isn't directly writable; stage in /data/local/tmp then
# copy in via run-as (debuggable build).
adb push "$MODEL" /data/local/tmp/model.gguf
adb shell run-as ai.edgelm.runtime cp /data/local/tmp/model.gguf files/model.gguf
adb shell run-as ai.edgelm.runtime ls -la files/

echo "== done. Launch Demo A, tap Generate, then run tools/measure_memory.sh =="
