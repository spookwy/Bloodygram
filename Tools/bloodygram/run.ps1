# Bloodygram: build a debug APK, start the emulator (AVD "epic") if needed, install and launch the app.
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File Tools\bloodygram\run.ps1
#   -Phone   build the installable APK for a real phone and copy it to the Desktop instead

param([switch]$Phone)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
Set-Location $root

if ($Phone) {
    & .\gradlew.bat :TMessagesProj_App:assembleAfatDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
    $apk = "TMessagesProj_App\build\outputs\apk\afat\debug\app.apk"
    Copy-Item $apk (Join-Path ([Environment]::GetFolderPath("Desktop")) "Bloodygram-debug.apk") -Force
    Write-Host "APK copied to Desktop\Bloodygram-debug.apk"
    exit 0
}

& .\gradlew.bat :TMessagesProj_App:assembleAfatDebug "-Pandroid.injected.build.abi=x86_64" --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$devices = & $adb devices | Select-String "emulator-"
if (-not $devices) {
    Write-Host "Starting emulator..."
    Start-Process $emulator -ArgumentList "-avd", "epic", "-no-snapshot-save", "-no-audio", "-no-boot-anim", "-gpu", "host"
    & $adb wait-for-device
    do {
        Start-Sleep -Seconds 3
        $booted = (& $adb shell getprop sys.boot_completed).Trim()
    } while ($booted -ne "1")
}

& $adb install -r -t "TMessagesProj_App\build\intermediates\apk\afat\debug\app.apk"
& $adb shell monkey -p com.epicgram.messenger.beta -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "Bloodygram is running in the emulator"
