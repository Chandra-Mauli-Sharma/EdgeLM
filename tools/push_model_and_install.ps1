# PowerShell: download (if given a URL) + build/install all modules, then push
# the GGUF model into the runtime-service's files dir so the service can mmap it.
#
# Usage (from the project root) — accepts either a local path OR a download URL:
#   .\tools\push_model_and_install.ps1 C:\path\to\model.gguf
#   .\tools\push_model_and_install.ps1 "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
param(
    [Parameter(Mandatory = $true)]
    [string]$Model
)

$ErrorActionPreference = "Stop"

function Require-Cmd($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        Write-Error "'$name' not found on PATH. (adb lives in <SDK>\platform-tools; add it to PATH.)"
        exit 1
    }
}
Require-Cmd adb

# --- resolve the model: download it if $Model is a URL -----------------------
if ($Model -match '^https?://') {
    $downloads = Join-Path $env:USERPROFILE "Downloads"
    # filename = last path segment, minus any ?query
    $fileName = ($Model -split '/')[-1] -replace '\?.*$', ''
    if ([string]::IsNullOrWhiteSpace($fileName)) { $fileName = "model.gguf" }
    $localPath = Join-Path $downloads $fileName

    if (Test-Path $localPath) {
        Write-Host "== model already downloaded: $localPath (skipping) ==" -ForegroundColor Green
    } else {
        Write-Host "== downloading model -> $localPath ==" -ForegroundColor Green
        # curl.exe follows HF's redirect to the CDN; -f fails loudly on HTTP errors
        curl.exe -L -f "$Model" -o "$localPath"
        if ($LASTEXITCODE -ne 0) { Write-Error "download failed"; exit 1 }
    }
} else {
    $localPath = $Model
}

if (-not (Test-Path $localPath)) {
    Write-Error "Model file not found: $localPath"
    exit 1
}
$sizeMB = [math]::Round((Get-Item $localPath).Length / 1MB, 1)
Write-Host "== model ready: $localPath ($sizeMB MB) ==" -ForegroundColor Green

# --- build + install all three modules ---------------------------------------
Write-Host "== building + installing modules ==" -ForegroundColor Green
.\gradlew.bat :runtime-service:installDebug :demo-app-a:installDebug :demo-app-b:installDebug
if ($LASTEXITCODE -ne 0) { Write-Error "Gradle install failed"; exit 1 }

# --- push the model into the runtime-service private files dir ----------------
Write-Host "== pushing model into runtime-service files dir ==" -ForegroundColor Green
adb push "$localPath" /data/local/tmp/model.gguf
adb shell run-as ai.edgelm.runtime cp /data/local/tmp/model.gguf files/model.gguf
adb shell run-as ai.edgelm.runtime ls -la files/

Write-Host "== done. Launch Demo A, tap Generate, then run tools\measure_memory.ps1 ==" -ForegroundColor Green
