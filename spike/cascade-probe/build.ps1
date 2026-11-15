# Standalone instrumentation APK; no production build inputs or output are modified.
$ErrorActionPreference = 'Stop'
$Probe = $PSScriptRoot
$Root = [IO.Path]::GetFullPath((Join-Path $Probe '../..'))
$Build = Join-Path $Probe 'build'
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$Bt = Join-Path $Sdk 'build-tools/34.0.0'
$Android = Join-Path $Sdk 'platforms/android-34/android.jar'
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME is required (JDK 17+)' }
$Java = Join-Path $env:JAVA_HOME 'bin'
$Ks = Join-Path $Root 'debug.keystore'
if (-not (Test-Path $Ks)) { throw 'Existing production-signature debug.keystore is required; never generate a replacement' }
New-Item -ItemType Directory -Force $Build, "$Build/classes" | Out-Null
function Check([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit $LASTEXITCODE" }
}
& "$Bt/aapt2.exe" link -I $Android --manifest "$Probe/AndroidManifest.xml" --min-sdk-version 24 --target-sdk-version 34 -o "$Build/base.apk"
Check 'manifest link'
$Sources = @(Get-ChildItem "$Probe/src" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& "$Java/javac.exe" -encoding UTF-8 -parameters -source 8 -target 8 -classpath $Android -d "$Build/classes" $Sources
Check 'javac'
& "$Java/jar.exe" -c -f "$Build/probe.jar" -C "$Build/classes" .
Check 'jar'
& "$Bt/d8.bat" --min-api 24 --lib $Android --output $Build "$Build/probe.jar"
Check 'd8'
Push-Location $Build
try {
    & "$Bt/aapt.exe" add "$Build/base.apk" classes.dex
    Check 'add dex'
} finally { Pop-Location }
& "$Bt/zipalign.exe" -f 4 "$Build/base.apk" "$Build/aligned.apk"
Check 'zipalign'
& "$Bt/apksigner.bat" sign --ks $Ks --ks-key-alias anyailive --ks-pass pass:android --key-pass pass:android --out "$Build/cascade-probe.apk" "$Build/aligned.apk"
Check 'sign'
& "$Bt/apksigner.bat" verify --verbose --print-certs "$Build/cascade-probe.apk"
Check 'verify'
& "$Bt/aapt.exe" dump badging "$Build/cascade-probe.apk"
Check 'manifest inspect'
Get-FileHash "$Build/cascade-probe.apk" -Algorithm SHA256
Write-Host 'Build only. No adb installation or device execution performed.'
