#Requires -Version 5.1
<#
.SYNOPSIS
停止 mall 三个后端服务（mall-admin / mall-portal / mall-search）和两个前端 dev server
（mall-admin-web / mall-app-web）。依赖容器保持运行。
#>
$ErrorActionPreference = 'SilentlyContinue'

Write-Host '正在停止后端服务 ...'
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'mall-(admin|portal|search)-1\.0-SNAPSHOT\.jar' }

if (-not $procs) {
    Write-Host '没有发现正在运行的 mall 服务。'
    exit 0
}

foreach ($p in $procs) {
    if ($p.CommandLine -match 'mall-admin-1\.0-SNAPSHOT\.jar')   { $which = 'mall-admin' }
    elseif ($p.CommandLine -match 'mall-portal-1\.0-SNAPSHOT\.jar') { $which = 'mall-portal' }
    else                                                         { $which = 'mall-search' }
    Write-Host "正在停止 $which (PID $($p.ProcessId)) ..."
    Stop-Process -Id $p.ProcessId -Force
}

Write-Host '正在停止前端 dev server ...'
$nodeProcs = Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
    Where-Object { $_.CommandLine -match 'mall-admin-web|mall-app-web' }
foreach ($n in $nodeProcs) {
    $which = if ($n.CommandLine -match 'mall-admin-web') { 'mall-admin-web' } else { 'mall-app-web' }
    Write-Host "正在停止 $which (PID $($n.ProcessId)) ..."
    Stop-Process -Id $n.ProcessId -Force
}

Write-Host 'mall 前后端服务已全部停止（依赖容器保持运行）。'
