# ドパチル インストーラ(WiX 不要)
#
# jpackage の MSI は WiX 3 を必要とする。入っていない環境でも
# 「インストールされた状態」を作れるようにするための、素朴な代わり。
#
#   - %LOCALAPPDATA%\Dopachiru に置く
#   - スタートメニューとデスクトップにショートカットを作る
#   - 「アプリと機能」に出るように登録する(消し方が分からないものを入れさせない)
#
# 管理者権限は要らない。書き込むのは自分のユーザーの領域だけ。

$ErrorActionPreference = 'Stop'

$appName = 'Dopachiru'
$source  = Join-Path $PSScriptRoot $appName
$target  = Join-Path $env:LOCALAPPDATA $appName
$exe     = Join-Path $target ($appName + '.exe')

if (-not (Test-Path $source)) {
    Write-Host ($appName + ' フォルダが見つかりません。zip を展開したフォルダの中で実行してください。') -ForegroundColor Red
    exit 1
}

$versionFile = Join-Path $PSScriptRoot 'version.txt'
if (Test-Path $versionFile) {
    $version = (Get-Content $versionFile -Raw).Trim()
} else {
    $version = '0.0.0'
}

# 動いていたら止める。掴まれたままだと上書きできない
$running = Get-Process -Name $appName -ErrorAction SilentlyContinue
if ($running) {
    Write-Host '動いているドパチルを終了します...'
    $running | Stop-Process -Force
    Start-Sleep -Milliseconds 700
}

Write-Host ($target + ' に置いています...')
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item -Path (Join-Path $source '*') -Destination $target -Recurse -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'uninstall.ps1') -Destination $target -Force

# ショートカット
$shell     = New-Object -ComObject WScript.Shell
$startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Dopachiru.lnk'
$desktop   = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Dopachiru.lnk'
foreach ($path in @($startMenu, $desktop)) {
    $lnk = $shell.CreateShortcut($path)
    $lnk.TargetPath       = $exe
    $lnk.WorkingDirectory = $target
    $lnk.Description      = 'Dopachiru'
    $lnk.Save()
}

# 「アプリと機能」に出す
$uninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Dopachiru'
New-Item -Path $uninstallKey -Force | Out-Null
Set-ItemProperty -Path $uninstallKey -Name 'DisplayName'     -Value 'Dopachiru'
Set-ItemProperty -Path $uninstallKey -Name 'DisplayVersion'  -Value $version
Set-ItemProperty -Path $uninstallKey -Name 'Publisher'       -Value 'dopachiru'
Set-ItemProperty -Path $uninstallKey -Name 'InstallLocation' -Value $target
Set-ItemProperty -Path $uninstallKey -Name 'DisplayIcon'     -Value $exe
Set-ItemProperty -Path $uninstallKey -Name 'NoModify'        -Value 1 -Type DWord
Set-ItemProperty -Path $uninstallKey -Name 'NoRepair'        -Value 1 -Type DWord
Set-ItemProperty -Path $uninstallKey -Name 'UninstallString' -Value ('powershell -NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $target 'uninstall.ps1') + '"')

Write-Host ''
Write-Host '入りました。' -ForegroundColor Green
Write-Host ('  場所: ' + $target)
Write-Host ('  版  : ' + $version)
Write-Host '  ショートカット: スタートメニュー / デスクトップ'
Write-Host ''
Write-Host 'ログイン時に自動で立ち上げたいときは、ドパチルを開いて 設定 → 起動 から入れてください。'
Write-Host ''
$answer = Read-Host 'いま起動しますか? (y/N)'
if ($answer -eq 'y') { Start-Process $exe }
