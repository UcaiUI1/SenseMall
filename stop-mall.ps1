#Requires -Version 5.1
<#
.SYNOPSIS
停止 mall 三个后端服务（mall-admin / mall-portal / mall-search）。
依赖容器（MySQL/Redis/MongoDB/RabbitMQ/MinIO/Elasticsearch）保持运行。
#>
$ErrorActionPreference = 'SilentlyContinue'

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
Write-Host 'mall 服务已全部停止。'
