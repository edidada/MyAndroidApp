# usb-adb-check.ps1 —— 一次性取证：手机到底有没有在 USB 层被枚举出来
# 用法（普通权限即可，不用管理员）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\usb-adb-check.ps1
# 目的是区分三件事：① 线/口的物理层问题 ② 驱动问题 ③ adb 授权问题

$ErrorActionPreference = 'SilentlyContinue'
Write-Host "==== 1) adb 能不能看到设备 ===="

$adbCandidates = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    "C:\Android\sdk\platform-tools\adb.exe"
)
$adb = $adbCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $adb) { $found = Get-Command adb.exe; if ($found) { $adb = $found.Source } }

if ($adb) {
    Write-Host "adb: $adb"
    & $adb version | Select-Object -Last 1 | ForEach-Object { Write-Host "     $_" }
    $list = & $adb devices -l | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' }
    if ($list) { $list | ForEach-Object { Write-Host "  DEV $_" } }
    else { Write-Host "  (adb devices 为空)" }
} else {
    Write-Host "本机没有 adb —— 下面 2/3/4 步不依赖 adb，结论照样有效"
}

Write-Host ""
Write-Host "==== 2) 在位的 USB 设备与其状态码 ===="
$usb = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like 'USB*' }
$usb | Select-Object Status, Problem, Class, FriendlyName, InstanceId |
    Format-Table -AutoSize

Write-Host "==== 3) 枚举失败/异常的设备（关键行）===="
$bad = $usb | Where-Object { $_.Status -ne 'OK' }
if ($bad) {
    $bad | ForEach-Object { Write-Host ("  BAD  {0,-28} {1}  [{2}]" -f $_.Problem, $_.FriendlyName, $_.InstanceId) }
} else {
    Write-Host "  没有异常状态的 USB 设备"
}

Write-Host "==== 4) 手机是否至少作为 MTP/便携设备出现了（能出现=线能传数据）===="
$wpd = Get-PnpDevice -PresentOnly | Where-Object { $_.Class -in @('WPD', 'WPDImageProvider', 'USBDevice') -or $_.FriendlyName -match 'MTP|Portable|Phone|Android|ADB' }
if ($wpd) { $wpd | Select-Object Status, Class, FriendlyName | Format-Table -AutoSize }
else { Write-Host "  没有 MTP/便携设备/ADB 接口 —— 手机没有把 USB 复合设备描述符递上来" }

Write-Host "==== 5) 最近 30 分钟 Kernel-PnP 失败事件（插拔历史）===="
$since = (Get-Date).AddMinutes(-30)
$ev = Get-WinEvent -FilterHashtable @{ LogName = 'Microsoft-Windows-Kernel-PnP/Configuration'; StartTime = $since } |
    Where-Object { $_.Id -in 410, 411, 420, 430, 440 }
if ($ev) {
    $ev | Select-Object -First 10 | ForEach-Object {
        $first = ($_ -split "`n")[0]
        Write-Host ("  {0}  Id={1}  {2}" -f $_.TimeCreated.ToString('HH:mm:ss'), $_.Id, $_.Message.Split("`n")[0].Trim())
    }
} else {
    Write-Host "  最近 30 分钟没有 PnP 失败事件（也可能是插拔发生在 30 分钟之前）"
}

Write-Host ""
Write-Host "==== 结论提示 ===="
$adbSeen = if ($adb) { (& $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' }).Count } else { 0 }
if ($adbSeen -gt 0) {
    Write-Host "  adb 已见设备：看它是 unauthorized（该在手机上点允许）还是 device（可以直接干活）"
} elseif ($bad) {
    Write-Host "  有设备但枚举失败(CM_PROB_*)：属物理层/信号问题 —— 优先换线，其次换直插主板后置口，别走 hub/KVM/前置面板"
} else {
    Write-Host "  USB 层完全没有新设备：手机没被识别为任何 USB 设备 —— 检查手机尾插、是否只充电、以及线的 D+/D-"
}
