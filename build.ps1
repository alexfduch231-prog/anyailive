# ASCII only. anyAIlive APK build without Gradle.
# Pipeline: aapt2 compile -> aapt2 link -> javac -> tests -> jar -> d8 -> aapt add -> zipalign -> apksigner
param([switch]$RequireExistingKey)
$ErrorActionPreference = "Continue"

$SDK = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android/Sdk" }
$BT    = "$SDK\build-tools\34.0.0"
$AJAR  = "$SDK\platforms\android-34\android.jar"
if (-not $env:JAVA_HOME) { throw "Set JAVA_HOME to a JDK (17 or newer; 17 is what we test with)" }; $JDK = Join-Path $env:JAVA_HOME "bin"
$ROOT = $PSScriptRoot
$APP   = "$ROOT\app"
$BUILD = "$ROOT\build"
$OUT   = "$ROOT\anyailive.apk"

$KS = Join-Path $ROOT "debug.keystore"
if ($RequireExistingKey -and -not (Test-Path $KS -PathType Leaf)) {
    throw "Release requires the existing debug.keystore; refusing to generate a new signing identity"
}

# version.txt: two lines, versionCode=<int> and versionName=<string>
$VC = 1
$VN = "0.0.0"
$VFILE = Join-Path $ROOT "version.txt"
if (Test-Path $VFILE) {
    foreach ($ln in Get-Content $VFILE) {
        if ($ln -match "^versionCode=(\d+)") { $VC = [int]$Matches[1] }
        if ($ln -match "^versionName=(\S+)") { $VN = $Matches[1] }
    }
}

$WV = Join-Path $APP "libs\nv-websocket-client-2.14.jar"
if (-not (Test-Path $WV)) {
    New-Item -ItemType Directory -Force (Split-Path $WV) | Out-Null
    Invoke-WebRequest -UseBasicParsing "https://repo.maven.apache.org/maven2/com/neovisionaries/nv-websocket-client/2.14/nv-websocket-client-2.14.jar" -OutFile $WV
}
$WV_SHA256 = "eed0fb6f5ef5db17d087039f1e829cfe827363b2863265258a96f0ed323313b7"
$got = (Get-FileHash $WV -Algorithm SHA256).Hash.ToLowerInvariant()
if ($got -ne $WV_SHA256) {
    throw ("nv-websocket-client-2.14.jar checksum mismatch: expected " + $WV_SHA256 + ", got " + $got)
}

function Step($name) { Write-Host ("==> " + $name) }
function Chk($code, $name) {
    if ($code -ne 0) { throw ("FAILED: " + $name + " (exit " + $code + ")") }
}

if (Test-Path $BUILD) { Remove-Item $BUILD -Recurse -Force }
New-Item -ItemType Directory -Path $BUILD | Out-Null
New-Item -ItemType Directory -Path "$BUILD\classes" | Out-Null
New-Item -ItemType Directory -Path "$BUILD\gen" | Out-Null

Step "aapt2 compile"
& "$BT\aapt2.exe" compile --dir "$APP\res" -o "$BUILD\res.zip" 2>&1 | ForEach-Object { "$_" }
Chk $LASTEXITCODE "aapt2 compile"

Step "aapt2 link"
& "$BT\aapt2.exe" link -I "$AJAR" `
    --manifest "$APP\AndroidManifest.xml" `
    --min-sdk-version 24 --target-sdk-version 34 `
    --version-code $VC --version-name $VN `
    --auto-add-overlay -A "$APP\assets" `
    --java "$BUILD\gen" `
    -o "$BUILD\unaligned.apk" `
    "$BUILD\res.zip" 2>&1 | ForEach-Object { "$_" }
Chk $LASTEXITCODE "aapt2 link"

Step "javac"
$srcs = @()
$srcs += (Get-ChildItem -Recurse "$BUILD\gen" -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem -Recurse "$APP\src" -Filter *.java | ForEach-Object { $_.FullName })
# -parameters is mandatory: without it javac 21 emits MethodParameters with empty name
# (name_index=0) for inner-class constructors and d8 from build-tools 34 crashes with NPE.
& "$JDK\javac.exe" -encoding UTF-8 -nowarn -parameters -source 8 -target 8 `
    -bootclasspath "$AJAR" -classpath "$WV" -d "$BUILD\classes" $srcs 2>&1 | ForEach-Object { "$_" }
Chk $LASTEXITCODE "javac"

Step "JVM tests"
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ROOT "test.ps1") -Classes (Join-Path $BUILD "classes")
Chk $LASTEXITCODE "JVM tests"

Step "jar"
# d8 crashes when fed loose .class files with '$' in names (MainActivity$1.class). Gradle always
# passes a jar, so we do the same.
& "$JDK\jar.exe" -c -f "$BUILD\classes.jar" -C "$BUILD\classes" . 2>&1 | ForEach-Object { "$_" }
Chk $LASTEXITCODE "jar"

Step "d8"
& "$BT\d8.bat" --min-api 24 --lib "$AJAR" --output "$BUILD" "$BUILD\classes.jar" "$WV" 2>&1 | ForEach-Object { "$_" }
Chk $LASTEXITCODE "d8"

Step "aapt add classes.dex"
Push-Location $BUILD
& "$BT\aapt.exe" add "$BUILD\unaligned.apk" "classes.dex" 2>&1 | ForEach-Object { "$_" }
$rc = $LASTEXITCODE
Pop-Location
Chk $rc "aapt add"

Step "zipalign"
& "$BT\zipalign.exe" -f 4 "$BUILD\unaligned.apk" "$BUILD\aligned.apk" 2>&1 | ForEach-Object { "$_" }
Chk $LASTEXITCODE "zipalign"

Step "keystore"
$KS = "$ROOT\debug.keystore"
if (-not (Test-Path $KS)) {
    if ($RequireExistingKey) { throw "Release signing key disappeared; refusing to generate a new key" }
    & "$JDK\keytool.exe" -genkeypair -v -keystore "$KS" `
        -storepass android -keypass android -alias anyailive `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=anyailive, OU=home, O=home, L=home, S=home, C=RU" 2>&1 | ForEach-Object { "$_" }
    Chk $LASTEXITCODE "keytool"
} else {
    Write-Host "keystore exists, reuse"
}

Step "apksigner"
if (Test-Path $OUT) { Remove-Item $OUT -Force }
& "$BT\apksigner.bat" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android `
    --out "$OUT" "$BUILD\aligned.apk" 2>&1 | ForEach-Object { "$_" }
Chk $LASTEXITCODE "apksigner sign"

Step "apksigner verify"
& "$BT\apksigner.bat" verify --print-certs "$OUT" 2>&1 | ForEach-Object { "$_" }
Chk $LASTEXITCODE "apksigner verify"

$fi = Get-Item $OUT
Step ("DONE: " + $OUT + " (" + [math]::Round($fi.Length/1024,1) + " KB)")
