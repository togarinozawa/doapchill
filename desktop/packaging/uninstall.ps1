# ドパチルを消す。install.ps1 が置いたものを、置いた順の逆に外していく。
#
# 設定と記録(%APPDATA%\dopachiru)は残す ── 入れ直したときに
# ルールとポイントが戻ってくるほうが、消えるより困らない。

$ErrorActionPreference = 'SilentlyContinue'

$appName = 'Dopachiru'
$target  = Join-Path $env:LOCALAPPDATA $appName

Get-Process -Name $appName | Stop-Process -Force
Start-Sleep -Milliseconds 700

# 自動起動の行。消さないと、消えた exe を指した行がログインのたびに空振りする
Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'Dopachiru'

Remove-Item (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Dopachiru.lnk')
Remove-Item (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Dopachiru.lnk')
Remove-Item -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Dopachiru' -Recurse

Write-Host '消しました。'
Write-Host '設定と記録( %APPDATA%\dopachiru )は残してあります。完全に消すときは手で削除してください。'

# このスクリプト自身が消す対象の中に居るので、抜けてから消す
Start-Process powershell -WindowStyle Hidden -ArgumentList @(
    '-NoProfile', '-Command',
    ("Start-Sleep -Seconds 2; Remove-Item -Recurse -Force '" + $target + "'")
)
