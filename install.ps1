# ドパチルを実機に入れる。
#
#   .\install.ps1            … dist の APK を入れる
#   .\install.ps1 -Build     … ビルドしてから入れる
#
# USB デバッグを有効にした端末を繋いでから実行してください。

param(
    [switch]$Build,
    [string]$Apk = "$PSScriptRoot\dist\dopachiru-0.4.1.apk"
)

$ErrorActionPreference = "Stop"
$adb = "C:\Users\hai\soft\android-sdk\platform-tools\adb.exe"
$pkg = "com.dopachiru"

if ($Build) {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
    $env:ANDROID_HOME = "C:\Users\hai\soft\android-sdk"
    & "$PSScriptRoot\gradlew.bat" -p $PSScriptRoot :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "ビルドに失敗しました" }
    Copy-Item "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk" $Apk -Force
}

if (-not (Test-Path $Apk)) { throw "APK が見つかりません: $Apk" }

Write-Host "`n[1/3] 端末の確認" -ForegroundColor Cyan
$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    throw "端末が繋がっていません。USB デバッグを有効にして、端末側の許可ダイアログで OK を押してください。"
}
& $adb shell getprop ro.product.manufacturer
& $adb shell getprop ro.product.model
& $adb shell getprop ro.build.version.release

Write-Host "`n[2/3] インストール" -ForegroundColor Cyan
& $adb install -r $Apk
if ($LASTEXITCODE -ne 0) { throw "インストールに失敗しました" }

Write-Host "`n[3/3] 制限された設定の解除" -ForegroundColor Cyan
# Android 13 以降、ストア以外から入れたアプリはこれを外さないと
# ユーザー補助のスイッチが押せない
& $adb shell appops set $pkg ACCESS_RESTRICTED_SETTINGS allow
Write-Host "  ACCESS_RESTRICTED_SETTINGS = allow"

Write-Host "`n完了。端末側で次をやってください:" -ForegroundColor Green
Write-Host "  1. 設定 → ユーザー補助 → ドパチル を ON"
Write-Host "  2. アプリを開いて、通知の許可を出す"
Write-Host "  3. 設定タブ → 電池の最適化から除外"
Write-Host "  4. 設定タブ → カレンダーの読み取りを許可(カレンダー連携を使う場合)"
Write-Host "  5. アプリ選択画面の「使用状況へのアクセス」を許可(任意。使用時間順で並べたい場合)"
Write-Host ""
Write-Host "ログを見る:" -ForegroundColor DarkGray
Write-Host "  $adb logcat --pid=`$($adb shell pidof -s $pkg)"
