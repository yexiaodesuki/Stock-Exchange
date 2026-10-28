# 一键启动证券环境：补齐五个基础容器，按需打包，配置中心就绪后启动客户端，不重置数据。
#requires -Version 5.1
param(
    [switch]$SkipBuild,
    [switch]$Rebuild,
    [switch]$NoBrowser,
    [ValidateRange(10, 600)][int]$StartupTimeoutSeconds = 120
)

# 读取简单 KEY=value 配置，不执行文件内容，也不把密码放到命令行或日志。
function Read-ExchangeSettings {
    param([string]$Path)
    if (!(Test-Path -LiteralPath $Path)) { throw '请先填写 build/.env.securities。' }
    $values = @{}
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $values[$matches[1]] = $matches[2] }
    }
    return $values
}

# 与 Spring 的外部覆盖习惯一致：进程环境优先，其次本地文件，最后使用现有配置的默认值。
function Get-ExchangeSetting {
    param([hashtable]$Settings, [string]$Name, [string]$Default = '')
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if (![string]::IsNullOrEmpty($value)) { return $value }
    if ($Settings.ContainsKey($Name)) { return $Settings[$Name] }
    return $Default
}

# 统一描述应用及其真实健康入口；管理端口与业务端口分开，Push 使用自身 Vert.x 健康接口。
function New-ExchangeApplicationPlan {
    param([string]$ProjectRoot, [hashtable]$Settings)
    $specs = @(
        @('config', 'CONFIG_PORT', 8888, 'CONFIG_MANAGEMENT_PORT', 18888, 'config', 'config-server'),
        @('trading-engine', 'SECURITIES_ENGINE_PORT', 8102, 'SECURITIES_ENGINE_MANAGEMENT_PORT', 18102, 'engine', 'securities-engine'),
        @('trading-sequencer', 'SECURITIES_SEQUENCER_PORT', 8103, 'SECURITIES_SEQUENCER_MANAGEMENT_PORT', 18103, 'health', 'securities-sequencer'),
        @('quotation', 'SECURITIES_QUOTATION_PORT', 8105, 'SECURITIES_QUOTATION_MANAGEMENT_PORT', 18105, 'health', 'securities-quotation'),
        @('trading-api', 'SECURITIES_API_PORT', 8101, 'SECURITIES_API_MANAGEMENT_PORT', 18101, 'health', 'securities-api'),
        @('push', 'SECURITIES_PUSH_PORT', 8106, 'SECURITIES_PUSH_MANAGEMENT_PORT', 18106, 'push', 'securities-push'),
        @('ui', 'SECURITIES_UI_PORT', 8100, 'SECURITIES_UI_MANAGEMENT_PORT', 18100, 'health', 'securities-ui')
    )
    foreach ($spec in $specs) {
        $port = [int](Get-ExchangeSetting $Settings $spec[1] ([string]$spec[2]))
        $management = [int](Get-ExchangeSetting $Settings $spec[3] ([string]$spec[4]))
        if ($port -lt 1 -or $port -gt 65535 -or $management -lt 1 -or $management -gt 65535) { throw '应用端口超出有效范围。' }
        $url = 'http://127.0.0.1:' + $management + '/actuator/health'
        if ($spec[5] -eq 'config') { $url = 'http://127.0.0.1:' + $port + '/securities-engine/default' }
        if ($spec[5] -eq 'engine') { $url = 'http://127.0.0.1:' + $port + '/internal/securities/status' }
        if ($spec[5] -eq 'push') { $url = 'http://127.0.0.1:' + $port + '/actuator/health' }
        [pscustomobject]@{
            Name = $spec[0]; Port = $port; ManagementPort = $management
            Kind = $spec[5]; ConfigName = $spec[6]; ReadyUrl = $url
            Jar = Join-Path $ProjectRoot ($spec[0] + '/target/' + $spec[0] + '.jar')
        }
    }
}

# 五个容器均存在且运行健康才允许跳过 Compose，不把容器存在当作已就绪。
function Test-ExchangeContainersReady {
    param([array]$States)
    foreach ($name in @('mysql', 'redis', 'kafka', 'grafana', 'prometheus')) {
        $rows = @($States | Where-Object { $_.Name -eq $name })
        if ($rows.Count -ne 1 -or $rows[0].State -ne 'running' -or $rows[0].Health -ne 'healthy') { return $false }
    }
    return $true
}

# 所有容器健康时完全跳过启动；否则只让 Compose 补齐，不删除卷或重建已有容器。
function Ensure-ExchangeContainers {
    param([scriptblock]$Inspect, [scriptblock]$StartContainers)
    if (Test-ExchangeContainersReady @(& $Inspect)) {
        Write-Host '五个 Docker 容器均已运行且健康，跳过容器启动。'
        return
    }
    Write-Host '正在补齐并等待 Docker 容器健康；首次运行可能需要下载镜像 ...'
    & $StartContainers
    if (!(Test-ExchangeContainersReady @(& $Inspect))) { throw 'Docker 容器未全部就绪，禁止启动 Java 应用。' }
}

# 只读取本 Compose 项目的名称和状态，不展开包含密码的容器配置。
function Get-ExchangeContainerStates {
    param([string]$Docker)
    $rows = & $Docker ps -a --filter 'label=com.docker.compose.project=stock-exchange-securities' --format '{{.Names}}|{{.State}}|{{.Status}}'
    if ($LASTEXITCODE -ne 0) { throw '无法连接 Docker，请先启动 Docker Desktop 并等待引擎就绪。' }
    foreach ($row in $rows) {
        $parts = $row -split '\|', 3
        [pscustomobject]@{ Name = $parts[0]; State = $parts[1]; Health = $(if ($parts[2] -match '\(healthy\)') { 'healthy' } else { 'not-ready' }) }
    }
}

# 输入包含源码、资源、所有模块 POM 与打包 SQL；不把日志、私密配置、外置 Config 仓库算作编译输入。
function Get-ExchangeBuildInputs {
    param([string]$ProjectRoot)
    foreach ($module in @('parent', 'common', 'config', 'push', 'quotation', 'trading-api', 'trading-engine', 'trading-sequencer', 'ui', 'build')) {
        Get-Item -LiteralPath (Join-Path $ProjectRoot ($module + '/pom.xml'))
        $source = Join-Path $ProjectRoot ($module + '/src/main')
        if (Test-Path -LiteralPath $source) { Get-ChildItem -LiteralPath $source -Recurse -File }
    }
    Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'build/sql/securities') -File -Filter 'V*.sql'
}

# 对路径与文件内容生成稳定指纹，识别修改、增加、删除以及 JAR 被外部重建；不打印文件内容。
function Get-ExchangeFilesFingerprint {
    param([string]$ProjectRoot, [array]$Files)
    $lines = foreach ($file in $Files | Sort-Object FullName) {
        $relative = $file.FullName.Substring($ProjectRoot.TrimEnd('\', '/').Length).Replace('\', '/')
        $relative + ':' + (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))))).Replace('-', '') }
    finally { $hash.Dispose() }
}

# 首次接管已有产物时核对时间；此后使用输入与产物双指纹，不因存在同名 JAR 就盲目跳过。
function Get-ExchangeBuildDecision {
    param([array]$Inputs, [array]$Jars, [string]$InputHash, [string]$JarHash, $Previous)
    if (@($Jars | Where-Object { !$_.Exists }).Count -gt 0) { return 'BUILD' }
    if ($null -ne $Previous) {
        if ($Previous.InputHash -eq $InputHash -and $Previous.JarHash -eq $JarHash) { return 'CACHED' }
        return 'BUILD'
    }
    $newestInput = ($Inputs | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).LastWriteTimeUtc
    $oldestJar = ($Jars | Sort-Object LastWriteTimeUtc | Select-Object -First 1).LastWriteTimeUtc
    if ($oldestJar -ge $newestInput) { return 'ADOPT' }
    return 'BUILD'
}

# TCP 只用于基础依赖可达性检查，不能代替 Java 应用的业务就绪检查。
function Test-ExchangeTcp {
    param([string]$HostName, [int]$Port)
    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect($HostName, $Port, $null, $null)
        if (!$pending.AsyncWaitHandle.WaitOne(1500)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false } finally { $client.Dispose() }
}

# 仅识别以当前项目完整 JAR 路径启动的 Java 进程；未知程序或 IDEA 实例不被误认、停止。
function Test-ExchangeProcessIdentity {
    param([string]$CommandLine, [string]$Jar)
    return $CommandLine -match ('(?i)(?:^|\s)-jar\s+"?' + [regex]::Escape($Jar) + '"?(?:\s|$)')
}

# 检查端口归属；只复用能够确认来源的本项目进程，拒绝悄悄连接其他项目。
function Get-ExchangeExistingProcess {
    param($Application)
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Application.Port -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) { return $null }
    $owners = @($listeners.OwningProcess | Select-Object -Unique)
    if ($owners.Count -ne 1) { throw ($Application.Name + ' 端口被多个进程占用。') }
    $owner = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $owners[0])
    if (!(Test-ExchangeProcessIdentity $owner.CommandLine $Application.Jar)) {
        throw ($Application.Name + ' 端口 ' + $Application.Port + ' 被非脚本可确认的进程占用；请检查 IDEA 或其他程序。')
    }
    return Get-Process -Id $owners[0] -ErrorAction Stop
}

# 就绪条件检查响应内容，而不是只看 HTTP 端口能否连接。
function Test-ExchangeReadyResponse {
    param([string]$Kind, $Response)
    if ($Kind -eq 'config') {
        if ($Response.name -ne 'securities-engine') { return $false }
        foreach ($item in $Response.propertySources) {
            if ($item.source.'securities.config-contract' -eq 'securities-v1') { return $true }
        }
        return $false
    }
    if ($Kind -eq 'engine') { return $Response.state -eq 'READY' }
    return $Response.status -eq 'UP'
}

# 等待期间若进程退出立即失败；超时不继续启动依赖它的后续应用。
function Wait-ExchangeApplicationReady {
    param($Application, [int]$TimeoutSeconds, [scriptblock]$Alive, [scriptblock]$Probe,
        [int]$RetryMilliseconds = 500)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        if (!(& $Alive)) { throw ($Application.Name + ' 在就绪前退出，请查看该应用日志。') }
        $response = $null
        try { $response = & $Probe $Application.ReadyUrl } catch { }
        if ($null -ne $response -and (Test-ExchangeReadyResponse $Application.Kind $response)) { return }
        Start-Sleep -Milliseconds $RetryMilliseconds
    }
    throw ($Application.Name + ' 启动超时，请查看该应用日志。')
}

# Config 必须第一个完成就绪与六份配置校验，任何失败直接终止序列。
function Invoke-ExchangeStartupSequence {
    param([array]$Applications, [scriptblock]$StartApplication, [scriptblock]$ConfirmConfigurations)
    if ($Applications.Count -ne 7 -or $Applications[0].Name -ne 'config') { throw '启动计划必须先配置中心，再六个业务应用。' }
    & $StartApplication $Applications[0]
    & $ConfirmConfigurations
    foreach ($application in $Applications | Select-Object -Skip 1) { & $StartApplication $application }
}

# 关闭失败启动中由本轮创建的进程；同时核对启动时间和完整 JAR，绝不按 java.exe 批量终止。
function Stop-ExchangeCreatedProcesses {
    param($Created)
    foreach ($record in @($Created.ToArray()) | Sort-Object Order -Descending) {
        $process = Get-Process -Id $record.ProcessId -ErrorAction SilentlyContinue
        if ($null -eq $process) { continue }
        $owner = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $record.ProcessId)
        if ($process.StartTime.Ticks -eq $record.StartTicks -and (Test-ExchangeProcessIdentity $owner.CommandLine $record.Jar)) {
            Stop-Process -Id $record.ProcessId -Force
        }
    }
}

# 创建隐藏后台进程并立即登记，以便启动失败时精确清理；密码不作为参数传递。
function Start-ExchangeApplication {
    param($Application, [string]$Java, [string]$ProjectRoot, [string]$RunDirectory,
        [string]$ConfigUrl, [int]$TimeoutSeconds, $Created, $Records)
    $process = Get-ExchangeExistingProcess $Application
    $reused = $null -ne $process
    if (!$reused) {
        $oldConfig = [Environment]::GetEnvironmentVariable('CONFIG_SERVER', 'Process')
        $hiddenSecrets = @{}
        try {
            [Environment]::SetEnvironmentVariable('CONFIG_SERVER', $ConfigUrl, 'Process')
            if ($Application.Name -eq 'config') {
                foreach ($item in Get-ChildItem Env: | Where-Object { $_.Name -match '^SEC' -and $_.Name -match 'PASSWORD|AUTH_KEY' }) {
                    $hiddenSecrets[$item.Name] = $item.Value
                    [Environment]::SetEnvironmentVariable($item.Name, $null, 'Process')
                }
            }
            $process = Start-Process -FilePath $Java -WorkingDirectory $ProjectRoot -WindowStyle Hidden -PassThru -ArgumentList @(
                '-Xmx256m', '-Dfile.encoding=UTF-8', '-jar', ('"' + $Application.Jar + '"'),
                ('--server.port=' + $Application.Port), ('--management.server.port=' + $Application.ManagementPort)
            ) -RedirectStandardOutput (Join-Path $RunDirectory ($Application.Name + '.out.log')) -RedirectStandardError (Join-Path $RunDirectory ($Application.Name + '.err.log'))
        } finally {
            [Environment]::SetEnvironmentVariable('CONFIG_SERVER', $oldConfig, 'Process')
            foreach ($key in $hiddenSecrets.Keys) { [Environment]::SetEnvironmentVariable($key, $hiddenSecrets[$key], 'Process') }
        }
    }
    $record = [pscustomobject]@{ Name = $Application.Name; ProcessId = $process.Id; StartTicks = $process.StartTime.Ticks
        Jar = $Application.Jar; Port = $Application.Port; Reused = $reused; Order = $Records.Count }
    [void]$Records.Add($record)
    if (!$reused) { [void]$Created.Add($record) }
    $Records.ToArray() | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $RunDirectory 'processes.json') -Encoding UTF8
    Write-Host ('等待 ' + $Application.Name + ' 就绪' + $(if ($reused) { '（复用已有进程）' } else { '' }) + ' ...')
    Wait-ExchangeApplicationReady $Application $TimeoutSeconds -Alive { $process.Refresh(); return !$process.HasExited } -Probe {
        param($url) Invoke-RestMethod -Uri $url -TimeoutSec 3
    }
    Write-Host ($Application.Name + ' 已就绪。')
}

# 统一启动入口；可点运行或从任意工作目录调用，应用始终以项目根目录运行。
function Start-ExchangeProject {
    param([string]$ProjectRoot, [switch]$SkipBuild, [switch]$Rebuild, [switch]$NoBrowser, [int]$TimeoutSeconds = 120)
    $ErrorActionPreference = 'Stop'
    if ($SkipBuild -and $Rebuild) { throw '-SkipBuild 与 -Rebuild 不能同时使用。' }
    $settings = Read-ExchangeSettings (Join-Path $ProjectRoot 'build/.env.securities')
    foreach ($key in @('SEC_MYSQL_PASSWORD', 'SECURITIES_AUTH_KEY')) {
        $value = Get-ExchangeSetting $settings $key
        if (!$value -or $value.StartsWith('replace-with-')) { throw ('请填写有效配置：' + $key) }
    }
    $java = (Get-Command java -ErrorAction Stop).Source
    $applications = @(New-ExchangeApplicationPlan $ProjectRoot $settings)
    $ports = @($applications | ForEach-Object { $_.Port; $_.ManagementPort } | Select-Object -Unique)
    if ($ports.Count -ne 14) { throw '应用业务端口与管理端口不能重复。' }
    $docker = (Get-Command docker -ErrorAction Stop).Source
    Ensure-ExchangeContainers -Inspect { Get-ExchangeContainerStates $docker } -StartContainers {
        & $docker compose --env-file (Join-Path $ProjectRoot 'build/.env.securities') -f (Join-Path $ProjectRoot 'build/docker-compose.yml') up -d --wait --wait-timeout 180 --no-recreate
        if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 启动失败；请检查 Docker 和镜像下载，不会继续启动 Java。' }
    }
    $dependencies = @(
        @('MySQL', (Get-ExchangeSetting $settings 'SECURITIES_JDBC_URL' 'jdbc:mysql://127.0.0.1:13306/exchange_securities')),
        @('Redis', (Get-ExchangeSetting $settings 'SEC_REDIS_URI' 'redis://127.0.0.1:16379/0')),
        @('Kafka', (Get-ExchangeSetting $settings 'SEC_KAFKA_BOOTSTRAP_SERVERS' '127.0.0.1:19092'))
    )
    foreach ($dependency in $dependencies) {
        $endpoint = [regex]::Match($dependency[1], '(?:^|://)([^/:,]+):(\d+)')
        if (!$endpoint.Success -or !(Test-ExchangeTcp $endpoint.Groups[1].Value ([int]$endpoint.Groups[2].Value))) {
            throw ($dependency[0] + ' 不可达；请先在 build 目录运行 docker compose --env-file .env.securities up -d --wait。')
        }
    }
    $existing = @($applications | ForEach-Object { Get-ExchangeExistingProcess $_ } | Where-Object { $null -ne $_ })
    foreach ($application in $applications) {
        if ($null -eq (Get-ExchangeExistingProcess $application)) {
            if (Get-NetTCPConnection -State Listen -LocalPort $application.ManagementPort -ErrorAction SilentlyContinue) {
                throw ($application.Name + ' 管理端口已被其他进程占用。')
            }
        }
    }
    $runDirectory = Join-Path $ProjectRoot ('build/.local-run/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    [void](New-Item -ItemType Directory -Path $runDirectory -Force)
    Write-Host ('本轮日志：' + $runDirectory)
    $inputs = @(Get-ExchangeBuildInputs $ProjectRoot)
    $jars = @($applications | ForEach-Object { [IO.FileInfo]::new($_.Jar) })
    $inputHash = Get-ExchangeFilesFingerprint $ProjectRoot $inputs
    $jarHash = ''
    if (@($jars | Where-Object { !$_.Exists }).Count -eq 0) { $jarHash = Get-ExchangeFilesFingerprint $ProjectRoot $jars }
    $statePath = Join-Path $ProjectRoot 'build/.local-run/build-state.json'
    $previous = $null
    if (Test-Path -LiteralPath $statePath) {
        try { $previous = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
    }
    $decision = Get-ExchangeBuildDecision $inputs $jars $inputHash $jarHash $previous
    if ($Rebuild) { $decision = 'BUILD' }
    if (!$SkipBuild -and $decision -eq 'BUILD') {
        if ($existing.Count -gt 0) { throw '需要重新打包，但本项目 Java 应用仍在运行；请先停止旧应用，防止覆盖其 JAR。' }
        $maven = (Get-Command mvn -ErrorAction Stop).Source
        Write-Host '正在打包全部应用，首次运行可能需要下载 Maven 依赖 ...'
        & $maven -f (Join-Path $ProjectRoot 'build/pom.xml') clean package '-DskipTests' *> (Join-Path $runDirectory 'build.log')
        if ($LASTEXITCODE -ne 0) { throw ('打包失败，请查看 ' + (Join-Path $runDirectory 'build.log')) }
        $jars = @($applications | ForEach-Object { Get-Item -LiteralPath $_.Jar })
        $jarHash = Get-ExchangeFilesFingerprint $ProjectRoot $jars
    } elseif ($SkipBuild) {
        Write-Host '已显式跳过打包，使用现有 JAR；此模式不保证产物与当前源码一致。'
    } else {
        Write-Host '已有 JAR 与构建输入匹配，跳过 Maven 打包。'
    }
    if (!$SkipBuild) {
        [pscustomobject]@{ InputHash = $inputHash; JarHash = $jarHash } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    }
    foreach ($application in $applications) {
        if (!(Test-Path -LiteralPath $application.Jar)) { throw ('缺少 ' + $application.Name + ' JAR；请停止已有应用后去掉 -SkipBuild 重试。') }
    }
    $created = New-Object 'System.Collections.Generic.List[object]'
    $records = New-Object 'System.Collections.Generic.List[object]'
    $configUrl = 'http://127.0.0.1:' + $applications[0].Port
    try {
        Invoke-ExchangeStartupSequence $applications -StartApplication {
            param($application)
            Start-ExchangeApplication $application $java $ProjectRoot $runDirectory $configUrl $TimeoutSeconds $created $records
        } -ConfirmConfigurations {
            foreach ($application in $applications | Select-Object -Skip 1) {
                $response = Invoke-RestMethod -Uri ($configUrl + '/' + $application.ConfigName + '/default') -TimeoutSec 5
                $valid = $false
                foreach ($item in $response.propertySources) {
                    if ($item.source.'securities.config-contract' -eq 'securities-v1') { $valid = $true }
                }
                if ($response.name -ne $application.ConfigName -or !$valid) { throw ($application.ConfigName + ' 未取得有效配置，禁止启动客户端。') }
            }
            Write-Host 'Config 的六份客户端配置校验通过，开始启动业务应用。'
        }
        $uiUrl = 'http://localhost:' + $applications[-1].Port
        Write-Host ('所有应用已就绪：' + $uiUrl)
        if (!$NoBrowser) {
            try { Start-Process $uiUrl -ErrorAction Stop }
            catch { Write-Warning ('应用已启动，但浏览器未能打开，请手动访问 ' + $uiUrl) }
        }
    } catch {
        Stop-ExchangeCreatedProcesses $created
        throw ('启动失败，已停止本轮新建的应用，已有应用不受影响。日志：' + $runDirectory + '；原因：' + $_.Exception.Message)
    }
}

# 点导入只加载函数，便于隔离测试；直接执行才连接依赖、启动应用和打开页面。
if ($MyInvocation.InvocationName -ne '.') {
    try { Start-ExchangeProject -ProjectRoot $PSScriptRoot -SkipBuild:$SkipBuild -Rebuild:$Rebuild -NoBrowser:$NoBrowser -TimeoutSeconds $StartupTimeoutSeconds }
    catch { Write-Error $_.Exception.Message; exit 1 }
}
