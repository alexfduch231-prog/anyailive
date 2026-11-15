# Runs already compiled JVM checks. Does not build or modify APK artifacts.
param(
    [string]$Classes = (Join-Path $PSScriptRoot "build/classes")
)
$ErrorActionPreference = "Stop"
if (-not $env:JAVA_HOME) { throw "Set JAVA_HOME to a JDK (17 or newer)" }
$JAVA = Join-Path $env:JAVA_HOME "bin/java.exe"
$WV = Join-Path $PSScriptRoot "app/libs/nv-websocket-client-2.14.jar"
if (-not (Test-Path $JAVA -PathType Leaf)) { throw "java.exe not found in JAVA_HOME" }
if (-not (Test-Path (Join-Path $Classes "ru/alexv/anyailive/TestMain.class") -PathType Leaf)) {
    throw "Compiled TestMain missing; run build.ps1 first"
}
if (-not (Test-Path $WV -PathType Leaf)) { throw "Missing nv-websocket-client-2.14.jar" }
& $JAVA -cp "$Classes;$WV" ru.alexv.anyailive.TestMain
if ($LASTEXITCODE -ne 0) { throw "JVM regression tests failed (exit $LASTEXITCODE)" }
