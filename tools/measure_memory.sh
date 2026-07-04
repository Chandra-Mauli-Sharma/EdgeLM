#!/usr/bin/env bash
# Phase 0 gate check: is the model counted ONCE when two apps use it?
#
# Usage:
#   1. Install runtime-service + both demo apps, push model.gguf (see below).
#   2. Open Demo A, tap Generate (spins up the :core service, mmaps the model).
#   3. Run:  tools/measure_memory.sh            # snapshot with A only
#   4. Open Demo B, tap Generate.
#   5. Run:  tools/measure_memory.sh            # snapshot with A + B
#   6. Compare: total model RAM should stay ~flat, not double.
set -euo pipefail

PKG_SERVICE_PROC="ai.edgelm.runtime:core"

pid="$(adb shell pgrep -f "$PKG_SERVICE_PROC" | tr -d '\r' | head -n1 || true)"
if [[ -z "${pid:-}" ]]; then
  echo "!! runtime-service (:core) not running. Open Demo A and tap Generate first."
  exit 1
fi

echo "== runtime-service :core PID = $pid =="
echo "-- summary (look at TOTAL PSS and the .gguf mapping) --"
adb shell dumpsys meminfo "$pid" | sed -n '1,40p'

echo
echo "-- model file mapping (should appear as shared/clean, mmap'd once) --"
adb shell run-as ai.edgelm.runtime cat /proc/"$pid"/smaps 2>/dev/null \
  | grep -A3 -i "model.gguf" || \
  echo "   (smaps needs a debuggable build or root; the dumpsys PSS delta above is the key signal)"

echo
echo ">> PASS if: attaching Demo B did NOT add ~model-size to total PSS."
echo ">> FAIL if: weights show as 'private dirty' or total PSS doubled — sharing is broken."
