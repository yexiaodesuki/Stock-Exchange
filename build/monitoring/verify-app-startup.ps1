# 应用启动验收：逐个验证真实管理端口，不提交交易；只关闭本脚本创建的 Java 进程。
param(
    # 真实启动会恢复状态、保存快照或消费已有消息，必须由操作者明确允许。
    [switch] $AllowApplicationStartup,
    [ValidateSet('ui', 'trading-api', 'trading-engine', 'trading-sequencer', 'quotation', 'push')]
    [string[]] $Applications = @('ui', 'trading-api', 'trading-engine', 'trading-sequencer', 'quotation', 'push')
)
$ErrorActionPreference = 'Stop'
if (-not $AllowApplicationStartup) { throw '此脚本会启动真实应用，可能保存恢复快照并处理已有 Kafka 消息。确认后显式传入 -AllowApplicationStartup。' }
$projectDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$runDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('exchange-monitoring-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDirectory | Out-Null
$ports = @{ 'ui'=18000; 'trading-api'=18001; 'trading-engine'=18002; 'trading-sequencer'=18003; 'quotation'=18005; 'push'=18006 }
$businessPorts = @{ 'ui'=8000; 'trading-api'=8001; 'trading-engine'=8002; 'trading-sequencer'=8003; 'quotation'=8005; 'push'=8006 }

# 校验所有目标端口，避免占用、覆盖或停止用户已经运行的应用。
foreach ($port in (@(8888,18888) + @($Applications | ForEach-Object { $ports[$_]; $businessPorts[$_] }))) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "端口 $port 已占用，请保留现有进程并改用手工验收。" }
}

# 启动限定路径的 Java 包，限制堆和处理器数量以适应本地低内存环境。
function Start-VerificationApplication([string] $Name, [string] $Jar) {
    $jarPath = Join-Path $projectDirectory $Jar
    if (-not (Test-Path -LiteralPath $jarPath)) { throw "缺少构建产物：$jarPath" }
    $arguments = @('-Xms24m','-Xmx144m','-XX:MaxMetaspaceSize=160m','-XX:ActiveProcessorCount=2','-jar',('"'+$jarPath+'"'))
    if ($Name -ne 'config') { $arguments += '--spring.datasource.hikari.maximum-pool-size=4' }
    return Start-Process -FilePath (Get-Command java).Source -ArgumentList $arguments -WorkingDirectory $projectDirectory `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDirectory "$Name.out.log") `
        -RedirectStandardError (Join-Path $runDirectory "$Name.err.log")
}

# 等待真实就绪；超时或进程退出时保留日志，不把启动打印当成就绪。
function Wait-VerificationReady([System.Diagnostics.Process] $Process, [int] $Port) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) { throw "验证进程提前退出：PID=$($Process.Id)，日志目录：$runDirectory" }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health/readiness" -TimeoutSec 3
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "管理端口 $Port 就绪超时，日志目录：$runDirectory"
}

$configProcess = $null
try {
    $configProcess = Start-VerificationApplication 'config' 'config/target/config-1.0.jar'
    Wait-VerificationReady $configProcess 18888
    $configMetrics = (Invoke-WebRequest 'http://127.0.0.1:18888/actuator/prometheus' -TimeoutSec 5).Content
    if ($configMetrics -notmatch 'exchange_component_ready') { throw '配置中心缺少就绪指标' }
    Write-Output 'config-server: readiness=UP, prometheus=OK'
    foreach ($name in $Applications) {
        $applicationProcess = $null
        try {
            $applicationProcess = Start-VerificationApplication $name "$name/target/$name.jar"
            Wait-VerificationReady $applicationProcess $ports[$name]
            $metrics = (Invoke-WebRequest "http://127.0.0.1:$($ports[$name])/actuator/prometheus" -TimeoutSec 5).Content
            if ($metrics -notmatch 'jvm_memory_used_bytes') { throw "$name 缺少 JVM 指标" }
            if ($metrics -notmatch 'exchange_component_ready') { throw "$name 缺少本地就绪指标" }
            if ($name -ne 'push') {
                $expectedCount = if ($name -in @('ui', 'trading-sequencer')) { 2 } else { 3 }
                $deadline = [DateTime]::UtcNow.AddSeconds(25)
                do {
                    $metrics = (Invoke-WebRequest "http://127.0.0.1:$($ports[$name])/actuator/prometheus" -TimeoutSec 5).Content
                    $dependencyLines = @($metrics -split "`n" | Where-Object { $_ -match '^exchange_dependency_up\{' })
                    if ($dependencyLines.Count -eq $expectedCount -and @($dependencyLines | Where-Object { $_ -notmatch ' 1\.0$| 1$' }).Count -eq 0) { break }
                    Start-Sleep -Milliseconds 500
                } while ([DateTime]::UtcNow -lt $deadline)
                if ($dependencyLines.Count -ne $expectedCount -or @($dependencyLines | Where-Object { $_ -notmatch ' 1\.0$| 1$' }).Count -gt 0) {
                    throw "$name 实际依赖探测未全部成功"
                }
            }
            Write-Output "$name`: readiness=UP, prometheus=OK, dependencies=OK"
        } finally {
            # 仅回收当前 Start-Process 返回的进程，不按名称批量结束 Java。
            if ($applicationProcess -and -not $applicationProcess.HasExited) { Stop-Process -Id $applicationProcess.Id; $applicationProcess.WaitForExit(10000) | Out-Null }
        }
    }
} finally {
    if ($configProcess -and -not $configProcess.HasExited) { Stop-Process -Id $configProcess.Id; $configProcess.WaitForExit(10000) | Out-Null }
    Write-Output "本次启动日志：$runDirectory"
}
