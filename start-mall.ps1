#Requires -Version 5.1
<#
.SYNOPSIS
一键启动 mall 项目本地环境：依赖容器 + 三个后端服务（mall-admin / mall-portal / mall-search）。

.PARAMETER Rebuild
强制重新构建 jar（默认 jar 已存在时跳过构建）。

.PARAMETER SkipBuild
跳过构建步骤。

.PARAMETER SkipContainers
跳过依赖容器的检查与启动（假定容器已在运行）。
#>
param(
    [switch]$Rebuild,
    [switch]$SkipBuild,
    [switch]$SkipContainers,
    [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$LogDir = Join-Path $Root 'logs'

# ---------- 常量 ----------
$EsImage    = 'elasticsearch:7.17.28'
$IkPlugin   = 'https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-7.17.28.zip'
$DataSourceUrl = 'jdbc:mysql://localhost:3305/mall?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false'

# ---------- 工具函数 ----------
function Test-CommandExists([string]$Cmd) {
    return [bool](Get-Command $Cmd -ErrorAction SilentlyContinue)
}

function Test-PortOpen([string]$HostName, [int]$Port, [int]$TimeoutMs = 3000) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Test-HttpUp([string]$Url) {
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
        return ($r.StatusCode -eq 200)
    } catch {
        return $false
    }
}

function Wait-Docker([int]$Seconds = 120) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        docker info *> $null
        if ($LASTEXITCODE -eq 0) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Wait-Port([string]$HostName, [int]$Port, [string]$Label, [int]$Seconds = 90) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortOpen $HostName $Port) {
            Write-Host "  [OK] $Label 就绪 ($HostName`:$Port)"
            return $true
        }
        Start-Sleep -Seconds 3
    }
    Write-Host "  [FAIL] $Label 未就绪"
    return $false
}

function Wait-EsHealth([int]$Seconds = 120) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-RestMethod -Uri 'http://localhost:9200/_cluster/health' -TimeoutSec 5
            if ($r.status -eq 'green' -or $r.status -eq 'yellow') {
                Write-Host "  [OK] Elasticsearch 就绪 (status=$($r.status))"
                return $true
            }
        } catch { }
        Start-Sleep -Seconds 3
    }
    Write-Host '  [FAIL] Elasticsearch 未就绪'
    return $false
}

function Get-Jdk17 {
    $candidates = @(
        'C:\Program Files\Microsoft\jdk-17*',
        'C:\Program Files\Eclipse Adoptium\jdk-17*',
        'C:\Program Files\Java\jdk-17*'
    )
    foreach ($c in $candidates) {
        foreach ($d in @(Get-ChildItem $c -Directory -ErrorAction SilentlyContinue)) {
            if (Test-Path (Join-Path $d.FullName 'bin\java.exe')) { return $d.FullName }
        }
    }
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return $env:JAVA_HOME }
    return $null
}

function Get-MongoHost {
    # 找到能访问到 mall-mongodb 容器(27017)的本机地址；
    # 若本机 127.0.0.1:27017 被旧版 MongoDB 占用，则回退到宿主机局域网 IP。
    try {
        $ips = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
            Where-Object { $_.AddressFamily -eq 'InterNetwork' } |
            ForEach-Object { $_.IPAddressToString }
    } catch {
        $ips = @()
    }
    foreach ($ip in $ips) {
        if ($ip -like '127.*' -or $ip -like '169.254.*') { continue }
        if (Test-PortOpen $ip 27017 2000) { return $ip }
    }
    return 'localhost'
}

# ---------- 容器管理 ----------
function Ensure-Container {
    param(
        [string]$Name,
        [string]$Image,
        [string[]]$Env = @(),
        [string[]]$Ports = @(),
        [string[]]$Cmd = @(),
        [string]$Volume = ''
    )
    $state = docker inspect -f '{{.State.Running}}' $Name 2>$null
    if ($LASTEXITCODE -eq 0) {
        if ($state -eq 'true') {
            Write-Host "  [OK] 容器 $Name 已在运行"
            return $true
        }
        Write-Host "  [..] 启动已存在的容器 $Name"
        docker start $Name | Out-Null
        return ($LASTEXITCODE -eq 0)
    }
    Write-Host "  [..] 创建并启动容器 $Name ($Image)"
    $args = @('run', '-d', '--name', $Name)
    foreach ($p in $Ports) { $args += '-p'; $args += $p }
    foreach ($e in $Env)   { $args += '-e'; $args += $e }
    if ($Volume)           { $args += '-v'; $args += $Volume }
    $args += $Image
    if ($Cmd.Count -gt 0)  { $args += $Cmd }
    docker @args
    return ($LASTEXITCODE -eq 0)
}

function Ensure-RabbitMqConfig {
    $users = docker exec mall-rabbitmq rabbitmqctl list_users 2>$null
    if (($users -join "`n") -notmatch 'mall') {
        Write-Host '  [..] 创建 RabbitMQ 用户 mall'
        docker exec mall-rabbitmq rabbitmqctl add_user mall mall 2>$null | Out-Null
        docker exec mall-rabbitmq rabbitmqctl set_user_tags mall administrator 2>$null | Out-Null
    }
    $vhosts = docker exec mall-rabbitmq rabbitmqctl list_vhosts 2>$null
    if (($vhosts -join "`n") -notmatch '/mall') {
        Write-Host '  [..] 创建 RabbitMQ vhost /mall 并授权'
        docker exec mall-rabbitmq rabbitmqctl add_vhost /mall 2>$null | Out-Null
        docker exec mall-rabbitmq rabbitmqctl set_permissions -p /mall mall ".*" ".*" ".*" 2>$null | Out-Null
    }
    Write-Host '  [OK] RabbitMQ 用户/vhost 已就绪'
}

function Ensure-EsPlugin {
    $plugins = docker exec mall-elasticsearch bin/elasticsearch-plugin list 2>$null
    if ($plugins -match 'analysis-ik') {
        Write-Host '  [OK] Elasticsearch IK 插件已安装'
        return $true
    }
    Write-Host '  [..] 安装 Elasticsearch IK 中文分词插件...'
    'y' | docker exec -i mall-elasticsearch bin/elasticsearch-plugin install $IkPlugin *> $null
    $plugins = docker exec mall-elasticsearch bin/elasticsearch-plugin list 2>$null
    if ($plugins -notmatch 'analysis-ik') {
        Write-Host '  [FAIL] IK 插件安装失败'
        return $false
    }
    Write-Host '  [..] 重启 Elasticsearch 激活插件'
    docker restart mall-elasticsearch | Out-Null
    return $true
}

function Ensure-MysqlData {
    $count = docker exec -e MYSQL_PWD=root mall-mysql mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='mall'"
    if ($LASTEXITCODE -eq 0 -and [int]$count -gt 0) {
        Write-Host "  [OK] MySQL mall 库已有数据 ($count 张表)"
        return $true
    }
    $sqlFile = Join-Path $Root 'document\sql\mall.sql'
    if (-not (Test-Path $sqlFile)) {
        Write-Host "  [FAIL] 找不到 $sqlFile"
        return $false
    }
    Write-Host '  [..] mall 库为空，正在导入 mall.sql ...'
    docker cp $sqlFile mall-mysql:/tmp/mall.sql | Out-Null
    docker exec -e MYSQL_PWD=root mall-mysql sh -c 'mysql -uroot < /tmp/mall.sql' | Out-Null
    return ($LASTEXITCODE -eq 0)
}

# ---------- 构建与启动 ----------
function Invoke-Build {
    param([string]$Jdk, [string]$Mvn)
    $jars = @(
        (Join-Path $Root 'mall-admin\target\mall-admin-1.0-SNAPSHOT.jar'),
        (Join-Path $Root 'mall-portal\target\mall-portal-1.0-SNAPSHOT.jar'),
        (Join-Path $Root 'mall-search\target\mall-search-1.0-SNAPSHOT.jar')
    )
    $need = $Rebuild -or (@($jars | Where-Object { -not (Test-Path $_) }).Count -gt 0)
    if (-not $need) {
        Write-Host '  [SKIP] 三个 jar 已存在（加 -Rebuild 可强制重新构建）'
        return $true
    }
    Write-Host '  [..] Maven 构建中（首次会下载依赖，可能需要几分钟）...'
    $oldJavaHome = $env:JAVA_HOME
    $oldPath = $env:Path
    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;$env:Path"
    try {
        & $Mvn -pl mall-admin,mall-portal,mall-search -am package -DskipTests "-Ddocker.skip=true"
        return ($LASTEXITCODE -eq 0)
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:Path = $oldPath
    }
}

function Start-MallApp {
    param(
        [string]$Name,
        [int]$Port,
        [string]$Jar,
        [string]$Jdk,
        [hashtable]$ExtraEnv = @{}
    )
    $health = "http://localhost:$Port/actuator/health"
    if (Test-HttpUp $health) {
        Write-Host "  [SKIP] $Name 已在运行 (port $Port)"
        return $true
    }
    if (-not (Test-Path $Jar)) {
        Write-Host "  [FAIL] 缺少 $Jar，请先构建"
        return $false
    }
    Set-Item "Env:SPRING_DATASOURCE_URL" $DataSourceUrl
    foreach ($k in $ExtraEnv.Keys) { Set-Item "Env:$k" $ExtraEnv[$k] }
    $outLog = Join-Path $LogDir "$Name.out.log"
    $errLog = Join-Path $LogDir "$Name.err.log"
    Write-Host "  [..] 启动 $Name ..."
    Start-Process -FilePath (Join-Path $Jdk 'bin\java.exe') -ArgumentList '-jar', $Jar `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden | Out-Null
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 4
        if (Test-HttpUp $health) {
            Write-Host "  [OK] $Name 启动完成 (http://localhost:$Port)"
            return $true
        }
    }
    Write-Host "  [FAIL] $Name 120 秒内未就绪，日志: $outLog"
    return $false
}

# ---------- 主流程 ----------
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Write-Host ''
Write-Host '========== mall 一键启动 =========='

Write-Host '[1/5] Docker 环境'
if (-not (Test-CommandExists 'docker')) {
    Write-Host '  [FAIL] 未找到 docker 命令，请先安装 Docker Desktop'
    exit 1
}
if (-not (Wait-Docker)) {
    $dd = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (Test-Path $dd) {
        Write-Host '  [..] 正在启动 Docker Desktop ...'
        Start-Process $dd | Out-Null
    }
    if (-not (Wait-Docker 180)) {
        Write-Host '  [FAIL] Docker 引擎启动失败'
        exit 1
    }
}
Write-Host '  [OK] Docker 引擎已就绪'

if (-not $SkipContainers) {
    Write-Host '[2/5] 依赖容器'
    Ensure-Container -Name 'mall-mysql' -Image 'mysql:5.7' `
        -Env @('MYSQL_ROOT_PASSWORD=root', 'MYSQL_DATABASE=mall', 'TZ=Asia/Shanghai') `
        -Ports @('3305:3306') `
        -Volume 'mall_mall-mysql-data:/var/lib/mysql' | Out-Null
    Ensure-Container -Name 'mall-redis' -Image 'redis:7.0' `
        -Cmd @('redis-server', '--appendonly', 'yes') `
        -Ports @('6379:6379') `
        -Volume 'mall-redis-data:/data' | Out-Null
    Ensure-Container -Name 'mall-mongodb' -Image 'mongo:5.0' `
        -Ports @('27017:27017') `
        -Volume 'mall-mongo-data:/data/db' | Out-Null
    Ensure-Container -Name 'mall-rabbitmq' -Image 'rabbitmq:3.10.5-management' `
        -Ports @('5672:5672', '15672:15672') `
        -Volume 'mall-rabbitmq-data:/var/lib/rabbitmq' | Out-Null
    Ensure-Container -Name 'mall-minio' -Image 'minio/minio' `
        -Env @('MINIO_ROOT_USER=minioadmin', 'MINIO_ROOT_PASSWORD=minioadmin') `
        -Cmd @('server', '/data', '--console-address', ':9001') `
        -Ports @('9000:9000', '9001:9001') `
        -Volume 'mall-minio-data:/data' | Out-Null
    Ensure-Container -Name 'mall-elasticsearch' -Image $EsImage `
        -Env @('discovery.type=single-node', 'cluster.name=elasticsearch', 'ES_JAVA_OPTS=-Xms512m -Xmx1024m') `
        -Ports @('9200:9200', '9300:9300') `
        -Volume 'mall-es-data:/usr/share/elasticsearch/data' | Out-Null

    Write-Host '  [..] 等待依赖服务就绪 ...'
    Wait-Port 'localhost' 3305 'MySQL' | Out-Null
    Wait-Port 'localhost' 6379 'Redis' | Out-Null
    Wait-Port 'localhost' 27017 'MongoDB' | Out-Null
    Wait-Port 'localhost' 5672 'RabbitMQ' | Out-Null
    Wait-Port 'localhost' 9000 'MinIO' 30 | Out-Null
    Ensure-EsPlugin | Out-Null
    Wait-EsHealth | Out-Null
    Ensure-RabbitMqConfig
    Ensure-MysqlData | Out-Null
} else {
    Write-Host '[2/5] 依赖容器（已跳过）'
}

Write-Host '[3/5] 构建'
$Jdk = Get-Jdk17
if (-not $Jdk) {
    Write-Host '  [FAIL] 未找到 JDK 17，请安装或在 JAVA_HOME 中指定'
    exit 1
}
if (-not (Test-CommandExists 'mvn')) {
    Write-Host '  [FAIL] 未找到 mvn，请安装 Maven'
    exit 1
}
$Mvn = (Get-Command 'mvn').Source
if ($SkipBuild) {
    Write-Host '  [SKIP] 已跳过构建（-SkipBuild）'
} elseif (-not (Invoke-Build -Jdk $Jdk -Mvn $Mvn)) {
    Write-Host '  [FAIL] Maven 构建失败'
    exit 1
}

Write-Host '[4/5] 启动服务'
$mongoHost = Get-MongoHost
Start-MallApp -Name 'mall-admin'  -Port 8080 -Jar (Join-Path $Root 'mall-admin\target\mall-admin-1.0-SNAPSHOT.jar')  -Jdk $Jdk | Out-Null
Start-MallApp -Name 'mall-portal' -Port 8085 -Jar (Join-Path $Root 'mall-portal\target\mall-portal-1.0-SNAPSHOT.jar') -Jdk $Jdk -ExtraEnv @{ SPRING_DATA_MONGODB_HOST = $mongoHost } | Out-Null
Start-MallApp -Name 'mall-search' -Port 8081 -Jar (Join-Path $Root 'mall-search\target\mall-search-1.0-SNAPSHOT.jar')  -Jdk $Jdk | Out-Null

if (-not $SkipFrontend) {
    Write-Host '[5/5] 前端项目'
    $frontends = @(
        @{ Name = 'mall-admin-web'; Dir = Join-Path $Root 'mall-admin-web'; Port = 8090; Script = 'dev';    Args = @('--', '--port', '8090', '--host') },
        @{ Name = 'mall-app-web';   Dir = Join-Path $Root 'mall-app-web';   Port = 8091; Script = 'dev:h5'; Args = @('--', '--port', '8091') }
    )
    $npm = $null
    $npmCmd = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if ($npmCmd) { $npm = $npmCmd.Source }
    elseif (Test-Path 'C:\Program Files\nodejs\npm.cmd') { $npm = 'C:\Program Files\nodejs\npm.cmd' }

    foreach ($fe in $frontends) {
        if (-not (Test-Path $fe.Dir)) {
            Write-Host "  [SKIP] $($fe.Name) 未找到（$($fe.Dir)），跳过"
            continue
        }
        if (Test-HttpUp "http://localhost:$($fe.Port)/") {
            Write-Host "  [SKIP] $($fe.Name) 已在运行 (port $($fe.Port))"
            continue
        }
        if (-not (Test-Path (Join-Path $fe.Dir 'node_modules'))) {
            if (-not $npm) { Write-Host "  [FAIL] 未找到 npm，无法安装 $($fe.Name) 依赖"; continue }
            Write-Host "  [..] 安装 $($fe.Name) 依赖 ..."
            Push-Location $fe.Dir
            try {
                & $npm install --no-audit --no-fund *> $null
                if ($LASTEXITCODE -ne 0) { Write-Host "  [FAIL] $($fe.Name) 依赖安装失败"; continue }
            } finally {
                Pop-Location
            }
        }
        if (-not $npm) { Write-Host "  [FAIL] 未找到 npm，无法启动 $($fe.Name)"; continue }
        Write-Host "  [..] 启动 $($fe.Name) (port $($fe.Port)) ..."
        $outLog = Join-Path $LogDir "$($fe.Name).log"
        $errLog = Join-Path $LogDir "$($fe.Name).err.log"
        $argList = @('run', $fe.Script) + $fe.Args
        Start-Process -FilePath $npm -ArgumentList $argList -WorkingDirectory $fe.Dir `
            -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden | Out-Null
        $deadline = (Get-Date).AddSeconds(120)
        $started = $false
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 4
            if (Test-HttpUp "http://localhost:$($fe.Port)/") {
                Write-Host "  [OK] $($fe.Name) 启动完成 (http://localhost:$($fe.Port))"
                $started = $true
                break
            }
        }
        if (-not $started) { Write-Host "  [FAIL] $($fe.Name) 未就绪，日志: $outLog" }
    }
} else {
    Write-Host '[5/5] 前端项目（已跳过）'
}

Write-Host ''
Write-Host '========== 启动汇总 =========='
foreach ($svc in @(@{ n = 'mall-admin';  p = 8080 }, @{ n = 'mall-portal'; p = 8085 }, @{ n = 'mall-search'; p = 8081 })) {
    $up = Test-HttpUp "http://localhost:$($svc.p)/actuator/health"
    Write-Host ("  {0,-14} http://localhost:{1,-6} {2}" -f $svc.n, $svc.p, $(if ($up) { '[UP]' } else { '[DOWN]' }))
}
Write-Host ''
Write-Host '接口文档:'
Write-Host '  mall-admin  -> http://localhost:8080/swagger-ui/index.html'
Write-Host '  mall-portal -> http://localhost:8085/swagger-ui/index.html'
Write-Host '  mall-search -> http://localhost:8081/swagger-ui/index.html'
Write-Host '前端页面:'
Write-Host '  后台管理    -> http://localhost:8090/'
Write-Host '  前台商城H5  -> http://localhost:8091/'
Write-Host '后台登录: admin / macro123'
Write-Host "日志目录: $LogDir"
Write-Host ''
