# ASCII only. Build and run the WSS protocol probes (one-off tool, not part of the apk).
# Requires JAVA_HOME (JDK 17+), GEMINI_API_KEY, optionally OPENAI_API_KEY.
$ErrorActionPreference = "Stop"
if (-not $env:JAVA_HOME) { throw "Set JAVA_HOME" }
$JDK  = Join-Path $env:JAVA_HOME "bin"
$ROOT = $PSScriptRoot
$JAR  = Join-Path (Split-Path $ROOT) "app\libs\nv-websocket-client-2.14.jar"
$OUT  = Join-Path $ROOT "build"
$DOCS = Join-Path (Split-Path (Split-Path $ROOT)) "docs\protocol-review"

if (-not (Test-Path $JAR)) { throw ("missing jar: " + $JAR) }
if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Path $OUT | Out-Null

Write-Host "==> javac"
& "$JDK\javac.exe" -encoding UTF-8 -cp $JAR -d $OUT (Get-ChildItem $ROOT -Filter *.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$cp = $OUT + ";" + $JAR
Write-Host "==> ProbeGemini"
Push-Location (Split-Path (Split-Path $ROOT))
$env:SPIKE_LOG = Join-Path $DOCS "spike-gemini.log"
& "$JDK\java.exe" -cp $cp ProbeGemini
Write-Host "==> ProbeOpenAI"
$env:SPIKE_LOG = Join-Path $DOCS "spike-openai.log"
& "$JDK\java.exe" -cp $cp ProbeOpenAI
Pop-Location
Write-Host "logs in $DOCS"
