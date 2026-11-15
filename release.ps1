# ASCII only. Builds the apk and prepares a release: dist/ + update-manifest.json
# Usage: powershell -File release.ps1 -Repo "owner/repo" [-Notes "text"]
param(
    [string]$Repo = "",
    [string]$Notes = ""
)
$ErrorActionPreference = "Stop"

$ROOT = $PSScriptRoot
$VC = 1
$VN = "0.0.0"
foreach ($ln in Get-Content (Join-Path $ROOT "version.txt")) {
    if ($ln -match "^versionCode=(\d+)") { $VC = [int]$Matches[1] }
    if ($ln -match "^versionName=(\S+)") { $VN = $Matches[1] }
}

if (-not $Repo) {
    $rf = Join-Path $ROOT "repo.txt"
    if (Test-Path $rf) { $Repo = (Get-Content $rf -TotalCount 1).Trim() }
}
if (-not $Repo) { throw "Pass -Repo owner/name or create repo.txt with one line owner/name" }
$Repo = $Repo.Trim()
if ($Repo -notmatch '^[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+$' -or $Repo -match '^owner/') {
    throw "Pass a real GitHub owner/repository, not the owner/anyailive placeholder"
}
if (-not (Test-Path (Join-Path $ROOT "debug.keystore") -PathType Leaf)) {
    throw "Release requires the existing debug.keystore; restore the original signing key"
}

Write-Host "==> build"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ROOT "build.ps1") -RequireExistingKey
if ($LASTEXITCODE -ne 0) { throw "build failed" }

$APK = Join-Path $ROOT "anyailive.apk"
$DIST = Join-Path $ROOT "dist"
New-Item -ItemType Directory -Force $DIST | Out-Null
$NAME = "anyailive-$VN.apk"
$TARGET = Join-Path $DIST $NAME
Copy-Item -Force $APK $TARGET

$SHA = (Get-FileHash $TARGET -Algorithm SHA256).Hash.ToLowerInvariant()
$SIZE = (Get-Item $TARGET).Length
$URL = "https://github.com/$Repo/releases/download/v$VN/$NAME"

$manifest = [ordered]@{
    versionCode = $VC
    versionName = $VN
    apk         = $NAME
    url         = $URL
    sha256      = $SHA
    size        = $SIZE
    minSdk      = 24
    notes       = $Notes
}
$json = $manifest | ConvertTo-Json -Depth 4
$mf = Join-Path $DIST "update-manifest.json"
[System.IO.File]::WriteAllText($mf, $json, [System.Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Host "==> dist"
Write-Host ("  " + $TARGET)
Write-Host ("  " + $mf)
Write-Host ("  sha256 " + $SHA)
Write-Host ""
Write-Host "==> next steps"
Write-Host ("  1) git tag v" + $VN + "; git push origin v" + $VN)
Write-Host ("  2) create release v" + $VN + " in GitHub, upload " + $NAME + " and update-manifest.json")
Write-Host ("  3) check: " + $URL)
