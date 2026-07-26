<#
.SYNOPSIS
  edgelm - PowerShell CLI for the EdgeLM on-device runtime (arch doc Part 13).

.DESCRIPTION
  Drives the runtime's OpenAI-compatible loopback shim (127.0.0.1:1408, DEBUG builds)
  over 'adb forward'. Native PowerShell port of tools/edgelm (bash). No chmod needed.

  Commands:
    .\tools\edgelm.ps1 forward             adb port-forward tcp:1408 (once per session)
    .\tools\edgelm.ps1 health              runtime liveness + warm model set
    .\tools\edgelm.ps1 models              list warm models
    .\tools\edgelm.ps1 ls                  Hub catalog: install/active/version/family/pin
    .\tools\edgelm.ps1 pull <id|family:f>  resolve + enqueue a durable, verified download
    .\tools\edgelm.ps1 pin <id>            pin a model to its installed version (rollback)
    .\tools\edgelm.ps1 unpin <id>          unpin a model
    .\tools\edgelm.ps1 run "your prompt"   completion from the active model
    .\tools\edgelm.ps1 embed "your text"   on-device embedding (needs: pull bge-small-en-v1.5)
    .\tools\edgelm.ps1 vectors add <col> "text"     store a doc in a local collection (RAG)
    .\tools\edgelm.ps1 vectors search <col> "query" semantic search a collection
    .\tools\edgelm.ps1 vectors ls                    list collections
    .\tools\edgelm.ps1 tools-demo ["prompt"]         demo OpenAI tool-calling (weather tool)
    .\tools\edgelm.ps1 agent "question"              agent loop: runtime runs built-in tools
    .\tools\edgelm.ps1 bench [n] [prompt]  time n runs: ttft, end-to-end + decode tok/s

  Env: EDGELM_HOST (default 127.0.0.1), EDGELM_PORT (default 1408),
       EDGELM_MODEL (default "default"). Requires: adb (for forward).

.NOTES
  If you hit "running scripts is disabled on this system", either run:
    powershell -ExecutionPolicy Bypass -File .\tools\edgelm.ps1 health
  or, once per user:
    Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
#>
param(
  [Parameter(Position = 0)] [string] $Command = "help",
  [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Rest
)

$ErrorActionPreference = "Stop"

$HostName = if ($env:EDGELM_HOST) { $env:EDGELM_HOST } else { "127.0.0.1" }
$Port     = if ($env:EDGELM_PORT) { $env:EDGELM_PORT } else { "1408" }
$Model    = if ($env:EDGELM_MODEL) { $env:EDGELM_MODEL } else { "default" }
$Base     = "http://${HostName}:${Port}"

function Fail($msg) { Write-Error "edgelm: $msg"; exit 1 }

function Invoke-Pin([string]$model, [bool]$pinned) {
  if (-not $model) { Fail 'usage: .\tools\edgelm.ps1 pin <id> (or unpin <id>)' }
  $body = @{ model = $model; pinned = $pinned } | ConvertTo-Json -Compress
  Invoke-RestMethod -Method Post -Uri "$Base/v1/edge/pin" -ContentType "application/json" -Body $body |
    ConvertTo-Json -Depth 6
}

function Invoke-Chat([string]$prompt, [bool]$stream) {
  $body = @{
    model    = $Model
    stream   = $stream
    messages = @(@{ role = "user"; content = $prompt })
  } | ConvertTo-Json -Depth 6 -Compress
  return Invoke-RestMethod -Method Post -Uri "$Base/v1/chat/completions" `
    -ContentType "application/json" -Body $body
}

switch ($Command.ToLower()) {

  "forward" {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "adb not found on PATH" }
    adb forward "tcp:$Port" "tcp:$Port" | Out-Null
    Write-Host "forwarded tcp:$Port to device tcp:$Port"
  }

  "health" {
    try { Invoke-RestMethod -Uri "$Base/health" | ConvertTo-Json -Depth 6 }
    catch { Fail "runtime not reachable. Is a DEBUG build running? try: .\tools\edgelm.ps1 forward" }
  }

  "models" {
    Invoke-RestMethod -Uri "$Base/v1/models" | ConvertTo-Json -Depth 6
  }

  "ls" {
    $d = Invoke-RestMethod -Uri "$Base/v1/edge/models"
    foreach ($m in $d.models) {
      $flags = @()
      if ($m.active) { $flags += "active" } elseif ($m.installed) { $flags += "installed" }
      if ($m.pinned_version -ge 0) { $flags += "pinned@$($m.pinned_version)" }
      "{0,-26} {1,-7} {2,5} MB  v{3}  {4,-10} {5}" -f `
        $m.id, $m.params, $m.size_mb, $m.version, $m.family, ($flags -join " ")
    }
  }

  "pull" {
    $model = ($Rest -join " ").Trim()
    if (-not $model) { Fail 'usage: .\tools\edgelm.ps1 pull <id|family:tiny|small|medium>' }
    $body = @{ model = $model } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$Base/v1/edge/pull" -ContentType "application/json" -Body $body |
      ConvertTo-Json -Depth 6
  }

  "pin"   { Invoke-Pin ($Rest -join " ").Trim() $true }
  "unpin" { Invoke-Pin ($Rest -join " ").Trim() $false }

  "batched-test" {
    Invoke-RestMethod -Method Post -Uri "$Base/v1/edge/batched-test" | ConvertTo-Json -Depth 6
    Write-Host "watch: adb logcat -s edgelm-batched-test  (tokens for seq 0/1 should interleave)"
  }

  "batched-mode" {
    $on = -not ($Rest.Count -ge 1 -and $Rest[0] -match '^(off|false|0)$')
    $body = @{ on = $on } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$Base/v1/edge/batched-mode" -ContentType "application/json" -Body $body |
      ConvertTo-Json -Depth 6
    Write-Host "live requests now route through the batched engine ($([bool]$on)); watch logcat 'edgelm-batched-svc'"
  }

  "run" {
    $prompt = ($Rest -join " ").Trim()
    if (-not $prompt) { Fail 'usage: .\tools\edgelm.ps1 run "your prompt"' }
    # Non-streaming for a clean single result (PowerShell + SSE is fiddly).
    $resp = Invoke-Chat $prompt $false
    Write-Host $resp.choices[0].message.content
  }

  "embed" {
    $text = ($Rest -join " ").Trim()
    if (-not $text) { Fail 'usage: .\tools\edgelm.ps1 embed "your text"' }
    $body = @{ input = $text } | ConvertTo-Json -Compress
    $resp = Invoke-RestMethod -Method Post -Uri "$Base/v1/embeddings" -ContentType "application/json" -Body $body
    if ($resp.error) { Write-Host $resp.error; break }
    $vec = $resp.data[0].embedding
    $head = ($vec[0..4] | ForEach-Object { "{0:N4}" -f $_ }) -join ", "
    Write-Host ("model {0} | dim {1} | first 5: [{2}, ...]" -f $resp.model, $vec.Count, $head)
  }

  "vectors" {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "" }
    switch ($sub) {
      "add" {
        if ($Rest.Count -lt 3) { Fail 'usage: .\tools\edgelm.ps1 vectors add <collection> "text"' }
        $col = $Rest[1]; $text = ($Rest[2..($Rest.Count-1)] -join " ")
        $body = @{ collection = $col; items = @(@{ text = $text }) } | ConvertTo-Json -Depth 6 -Compress
        Invoke-RestMethod -Method Post -Uri "$Base/v1/edge/vectors/upsert" -ContentType "application/json" -Body $body | ConvertTo-Json -Depth 6
      }
      "search" {
        if ($Rest.Count -lt 3) { Fail 'usage: .\tools\edgelm.ps1 vectors search <collection> "query"' }
        $col = $Rest[1]; $q = ($Rest[2..($Rest.Count-1)] -join " ")
        $body = @{ collection = $col; query = $q; top_k = 5 } | ConvertTo-Json -Compress
        $resp = Invoke-RestMethod -Method Post -Uri "$Base/v1/edge/vectors/query" -ContentType "application/json" -Body $body
        if ($resp.error) { Write-Host $resp.error; break }
        foreach ($m in $resp.matches) { "{0:N3}  {1}" -f $m.score, $m.text }
      }
      "ls" { Invoke-RestMethod -Uri "$Base/v1/edge/vectors/collections" | ConvertTo-Json -Depth 6 }
      default { Fail 'usage: .\tools\edgelm.ps1 vectors add|search|ls ...' }
    }
  }

  "tools-demo" {
    $prompt = if ($Rest.Count -ge 1) { ($Rest -join " ") } else { "What's the weather in Paris?" }
    $body = @{
      model = $Model; stream = $false
      messages = @(@{ role = "user"; content = $prompt })
      tools = @(@{ type = "function"; function = @{
        name = "get_weather"; description = "Get the current weather for a city"
        parameters = @{ type = "object"; properties = @{ city = @{ type = "string"; description = "City name" } }; required = @("city") }
      } })
    } | ConvertTo-Json -Depth 10 -Compress
    $resp = Invoke-RestMethod -Method Post -Uri "$Base/v1/chat/completions" -ContentType "application/json" -Body $body
    $c = $resp.choices[0].message
    if ($c.tool_calls) { Write-Host ("tool_call: {0}({1})" -f $c.tool_calls[0].function.name, $c.tool_calls[0].function.arguments) }
    else { Write-Host ("content: {0}" -f $c.content) }
  }

  "agent" {
    $prompt = ($Rest -join " ").Trim()
    if (-not $prompt) { Fail 'usage: .\tools\edgelm.ps1 agent "question"' }
    $body = @{ prompt = $prompt } | ConvertTo-Json -Compress
    $resp = Invoke-RestMethod -Method Post -Uri "$Base/v1/edge/agent" -ContentType "application/json" -Body $body
    if ($resp.error) { Write-Host $resp.error; break }
    foreach ($s in $resp.steps) { Write-Host ("  [tool] {0} -> {1}" -f $s.tool, $s.result) }
    Write-Host ("answer: {0}" -f $resp.answer)
  }

  "bench" {
    $n = 3; $prompt = "Write one sentence about the ocean."
    if ($Rest.Count -ge 1 -and $Rest[0] -match '^\d+$') { $n = [int]$Rest[0] }
    if ($Rest.Count -ge 2) { $prompt = ($Rest[1..($Rest.Count-1)] -join " ") }
    elseif ($Rest.Count -ge 1 -and $Rest[0] -notmatch '^\d+$') { $prompt = ($Rest -join " ") }

    Write-Host "benchmarking $n run(s) on '$Model'..."
    $totalTok = 0; $totalMs = 0; $totalTtft = 0; $totalDecodeMs = 0
    for ($i = 1; $i -le $n; $i++) {
      $resp = Invoke-Chat $prompt $false
      $tok  = [int]$resp.usage.completion_tokens
      $ms   = [int]$resp.edge.elapsed_ms
      $ttft = [int]$resp.edge.ttft_ms
      $decodeMs = [Math]::Max(0, $ms - $ttft)     # steady-state decode window
      $tps  = if ($ms -gt 0) { "{0:N1}" -f ($tok * 1000.0 / $ms) } else { "?" }
      $dtps = if ($decodeMs -gt 0) { "{0:N1}" -f (($tok - 1) * 1000.0 / $decodeMs) } else { "?" }
      Write-Host ("  run {0}: {1} tok | ttft {2} ms | end-to-end {3} tok/s | decode {4} tok/s" -f $i, $tok, $ttft, $tps, $dtps)
      $totalTok += $tok; $totalMs += $ms; $totalTtft += $ttft; $totalDecodeMs += $decodeMs
    }
    if ($totalMs -gt 0) {
      $avgTtft = [int]($totalTtft / $n)
      $avgE2E  = $totalTok * 1000.0 / $totalMs
      $avgDec  = if ($totalDecodeMs -gt 0) { ($totalTok - $n) * 1000.0 / $totalDecodeMs } else { 0 }
      Write-Host ("average: end-to-end {0:N1} tok/s | decode {1:N1} tok/s | ttft {2} ms" -f $avgE2E, $avgDec, $avgTtft)
    }
  }

  default {
    Get-Help $PSCommandPath -Detailed
  }
}
