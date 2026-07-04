# PowerShell: Phase 0 gate check — is the model counted ONCE across two apps?
#
# 1. Open Demo A, tap Generate (spins up the :core service, mmaps the model).
# 2. .\tools\measure_memory.ps1            # snapshot with A only
# 3. Open Demo B, tap Generate.
# 4. .\tools\measure_memory.ps1            # snapshot with A + B
# 5. Compare: total model RAM should stay ~flat, not double.

$procName = "ai.edgelm.runtime:core"
$svcPid = (adb shell pgrep -f $procName) -replace "`r", ""
$svcPid = ($svcPid -split "`n" | Where-Object { $_ -ne "" } | Select-Object -First 1)

if (-not $svcPid) {
    Write-Host "!! runtime-service (:core) not running. Open Demo A and tap Generate first." -ForegroundColor Yellow
    exit 1
}

Write-Host "== runtime-service :core PID = $svcPid ==" -ForegroundColor Green
Write-Host "-- summary (look at TOTAL PSS and the .gguf mapping) --"
adb shell dumpsys meminfo $svcPid

Write-Host ""
Write-Host ">> PASS if attaching Demo B did NOT add ~model-size to total PSS." -ForegroundColor Cyan
Write-Host ">> FAIL if weights show as 'private dirty' or total PSS doubled." -ForegroundColor Cyan
