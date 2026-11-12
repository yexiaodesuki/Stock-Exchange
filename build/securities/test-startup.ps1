# 一键启动脚本隔离测试：只导入函数，验证顺序、就绪、失败阻断与进程归属，不连接数据库或启动 Java。
#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $projectRoot 'start.ps1')

# 简单断言不依赖额外测试框架；失败以非零退出码返回。
function Assert-Startup {
    param([bool]$Condition, [string]$Message)
    if (!$Condition) { throw $Message }
}

$plan = @(New-ExchangeApplicationPlan $projectRoot @{})
Assert-Startup (($plan.Name -join ',') -eq 'config,trading-engine,trading-sequencer,quotation,trading-api,push,ui') '默认启动顺序错误'
Assert-Startup ($plan[0].ReadyUrl -eq 'http://127.0.0.1:8888/securities-engine/default') 'Config 必须验证真实配置接口'
Assert-Startup ($plan[-1].ReadyUrl -eq 'http://127.0.0.1:18100/actuator/health') 'UI 必须验证就绪而非只验证静态页面'
Write-Host 'PASS 1：应用顺序和健康入口'

$trace = New-Object 'System.Collections.Generic.List[string]'
Invoke-ExchangeStartupSequence $plan -StartApplication { param($app) [void]$trace.Add($app.Name) } -ConfirmConfigurations { [void]$trace.Add('config-verified') }
Assert-Startup (($trace.ToArray() -join ',') -eq 'config,config-verified,trading-engine,trading-sequencer,quotation,trading-api,push,ui') 'Config 校验必须早于第一个客户端'
Write-Host 'PASS 2：配置校验完成后才启动客户端'

$trace.Clear()
$blocked = $false
try {
    Invoke-ExchangeStartupSequence $plan -StartApplication { param($app) [void]$trace.Add($app.Name) } -ConfirmConfigurations { throw '模拟配置不可用' }
} catch { $blocked = $true }
Assert-Startup ($blocked -and $trace.Count -eq 1 -and $trace[0] -eq 'config') '配置失败不能继续启动业务'
Write-Host 'PASS 3：配置失败阻断'

$trace.Clear()
$blocked = $false
try {
    Invoke-ExchangeStartupSequence $plan -StartApplication {
        param($app)
        [void]$trace.Add($app.Name)
        if ($app.Name -eq 'trading-engine') { throw '模拟恢复失败' }
    } -ConfirmConfigurations { }
} catch { $blocked = $true }
Assert-Startup ($blocked -and ($trace.ToArray() -join ',') -eq 'config,trading-engine') '引擎失败不能继续启动后续应用'
Write-Host 'PASS 4：引擎失败阻断'

$wrong = [pscustomobject]@{ name = 'securities-engine'; propertySources = @([pscustomobject]@{ source = @{ 'securities.config-contract' = 'old' } }) }
$right = [pscustomobject]@{ name = 'securities-engine'; propertySources = @([pscustomobject]@{ source = @{ 'securities.config-contract' = 'securities-v1' } }) }
Assert-Startup (!(Test-ExchangeReadyResponse 'config' $wrong)) '错误配置版本不能放行'
Assert-Startup (Test-ExchangeReadyResponse 'config' $right) '正确配置应放行'
Assert-Startup (!(Test-ExchangeReadyResponse 'engine' ([pscustomobject]@{ state = 'RECOVERING' }))) '恢复中不能作为就绪'
Assert-Startup (Test-ExchangeReadyResponse 'engine' ([pscustomobject]@{ state = 'READY' })) '引擎就绪应放行'
Assert-Startup (!(Test-ExchangeReadyResponse 'health' ([pscustomobject]@{ status = 'DOWN' }))) '健康失败不能放行'
Write-Host 'PASS 5：配置契约与业务就绪内容'

$state = [pscustomobject]@{ Calls = 0 }
Wait-ExchangeApplicationReady $plan[1] 2 -RetryMilliseconds 1 -Alive { return $true } -Probe {
    param($url)
    $state.Calls++
    return [pscustomobject]@{ state = $(if ($state.Calls -lt 3) { 'RECOVERING' } else { 'READY' }) }
}
Assert-Startup ($state.Calls -eq 3) '应该等待真实就绪'
$blocked = $false
try { Wait-ExchangeApplicationReady $plan[1] 2 -Alive { return $false } -Probe { throw '不应执行探测' } } catch { $blocked = $true }
Assert-Startup $blocked '进程退出必须立即失败'
$blocked = $false
try { Wait-ExchangeApplicationReady $plan[1] 1 -RetryMilliseconds 10 -Alive { return $true } -Probe { return [pscustomobject]@{ state = 'RECOVERING' } } } catch { $blocked = $true }
Assert-Startup $blocked '持续恢复必须超时失败'
Write-Host 'PASS 6：恢复等待、退出检测与超时'

$jar = Join-Path $projectRoot 'config/target/config.jar'
Assert-Startup (Test-ExchangeProcessIdentity ('java -jar "' + $jar + '" --server.port=8888') $jar) '本项目完整路径应该被识别'
Assert-Startup (!(Test-ExchangeProcessIdentity 'java -jar D:\other\config.jar' $jar)) '不得复用或停止其他项目'
Assert-Startup (!(Test-ExchangeProcessIdentity 'java com.itranswarp.exchange.ConfigApplication' $jar)) '无法确认归属的 IDEA 进程应拒绝'
Write-Host 'PASS 7：带空格路径与未知进程保护'

$testKey = 'SECURITIES_STARTUP_TEST_VALUE'
$previous = [Environment]::GetEnvironmentVariable($testKey, 'Process')
try {
    [Environment]::SetEnvironmentVariable($testKey, $null, 'Process')
    Assert-Startup ((Get-ExchangeSetting @{} $testKey 'fallback') -eq 'fallback') '默认值错误'
    Assert-Startup ((Get-ExchangeSetting @{ $testKey = 'file' } $testKey 'fallback') -eq 'file') '文件值错误'
    [Environment]::SetEnvironmentVariable($testKey, 'environment', 'Process')
    Assert-Startup ((Get-ExchangeSetting @{ $testKey = 'file' } $testKey 'fallback') -eq 'environment') '环境变量覆盖错误'
} finally { [Environment]::SetEnvironmentVariable($testKey, $previous, 'Process') }
Write-Host 'PASS 8：配置优先级且不修改父环境'

$healthy = @('mysql', 'redis', 'kafka', 'nginx') | ForEach-Object { [pscustomobject]@{ Name = $_; State = 'running'; Health = 'healthy' } }
$state = [pscustomobject]@{ Starts = 0; Inspections = 0 }
Ensure-ExchangeContainers -Inspect { return $healthy } -StartContainers { $state.Starts++ }
Assert-Startup ($state.Starts -eq 0) '四个核心容器健康时不依赖可选组，也不重复执行 Compose up'
Assert-Startup (!(Test-ExchangeContainersReady @($healthy | Where-Object { $_.Name -ne 'kafka' }))) '缺容器不能跳过'
Assert-Startup (!(Test-ExchangeContainersReady @($healthy | Where-Object { $_.Name -ne 'nginx' }))) '缺网关不能跳过'
Assert-Startup (Test-ExchangeReadyResponse 'gateway' ([pscustomobject]@{csrf='proof';websocket='/ws'})) '网关会话链路应就绪'
Assert-Startup (!(Test-ExchangeReadyResponse 'gateway' ([pscustomobject]@{csrf='proof';websocket='ws://localhost:8106/ws'}))) '不能绕过网关连接推送'
Write-Host 'PASS 9：全部健康时跳过 Docker 启动'

$stopped = @($healthy | ForEach-Object { [pscustomobject]@{ Name = $_.Name; State = $(if ($_.Name -eq 'redis') { 'exited' } else { 'running' }); Health = $_.Health } })
$state.Starts = 0
$state.Inspections = 0
Ensure-ExchangeContainers -Inspect {
    $state.Inspections++
    if ($state.Inspections -eq 1) { return $stopped }
    return $healthy
} -StartContainers { $state.Starts++ }
Assert-Startup ($state.Starts -eq 1 -and $state.Inspections -eq 2) '未运行容器应只补齐一次，再确认健康'
$blocked = $false
try { Ensure-ExchangeContainers -Inspect { return $stopped } -StartContainers { throw '模拟 Docker 不可用' } } catch { $blocked = $true }
Assert-Startup $blocked 'Docker 失败应阻断 Java 启动'
Write-Host 'PASS 10：补齐、复核与失败阻断'

$stamp = [DateTime]::UtcNow
$inputs = @([pscustomobject]@{ LastWriteTimeUtc = $stamp })
$jars = @([pscustomobject]@{ Exists = $true; LastWriteTimeUtc = $stamp.AddMinutes(1) })
Assert-Startup ((Get-ExchangeBuildDecision $inputs $jars 'source' 'jar' $null) -eq 'ADOPT') '第一次应接管时间匹配的已有构建'
Assert-Startup ((Get-ExchangeBuildDecision $inputs $jars 'source' 'jar' ([pscustomobject]@{ InputHash = 'source'; JarHash = 'jar' })) -eq 'CACHED') '相同指纹不应重复打包'
Write-Host 'PASS 11：已有构建接管和缓存命中'

Assert-Startup ((Get-ExchangeBuildDecision $inputs @([pscustomobject]@{ Exists = $false }) 'source' '' $null) -eq 'BUILD') '缺少 JAR 必须构建'
Assert-Startup ((Get-ExchangeBuildDecision $inputs @([pscustomobject]@{ Exists = $true; LastWriteTimeUtc = $stamp.AddMinutes(-1) }) 'source' 'jar' $null) -eq 'BUILD') '旧产物必须构建'
Assert-Startup ((Get-ExchangeBuildDecision $inputs $jars 'changed' 'jar' ([pscustomobject]@{ InputHash = 'source'; JarHash = 'jar' })) -eq 'BUILD') '源码变化必须构建'
Assert-Startup ((Get-ExchangeBuildDecision $inputs $jars 'source' 'external-jar' ([pscustomobject]@{ InputHash = 'source'; JarHash = 'jar' })) -eq 'BUILD') '外部替换产物不能冒充缓存命中'
Write-Host 'PASS 12：缺失、过期、源码和产物变化识别'

$fixture = Join-Path ([IO.Path]::GetTempPath()) ('exchange-startup-test-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($fixture)
$file = Join-Path $fixture 'sample.txt'
try {
    [IO.File]::WriteAllText($file, 'before')
    $first = Get-ExchangeFilesFingerprint $fixture @(Get-Item -LiteralPath $file)
    $same = Get-ExchangeFilesFingerprint $fixture @(Get-Item -LiteralPath $file)
    [IO.File]::WriteAllText($file, 'after')
    $changed = Get-ExchangeFilesFingerprint $fixture @(Get-Item -LiteralPath $file)
    Assert-Startup ($first -eq $same -and $first -ne $changed) '真实文件内容指纹错误'
} finally {
    [IO.File]::Delete($file)
    [IO.Directory]::Delete($fixture)
}
$realInputs = @(Get-ExchangeBuildInputs $projectRoot)
Assert-Startup (@($realInputs | Where-Object { $_.FullName -match 'build[\\/]sql[\\/]securities[\\/]V' }).Count -gt 0) '必须纳入打包 SQL'
Assert-Startup (@($realInputs | Where-Object { $_.Name -eq '.env.securities' -or $_.FullName -match 'config-repo[\\/]' }).Count -eq 0) '私密配置和外置配置不应触发 Java 重编译'
Write-Host 'PASS 13：实际内容哈希和输入边界'
# 新知识服务的失败不能阻断交易，禁用时不读取目录或执行Docker。
Ensure-ExchangeRag -Docker 'unused' -ProjectRoot 'Z:/does-not-exist' -Settings @{SEC_RAG_ENABLED='false'} -RunDirectory 'unused' -Inspect { throw '禁用时不应探测' } -BuildRag { throw '禁用时不应构建' }
# 外部模式不能访问本地模型源码，也只能启动 Chroma。
Ensure-ExchangeRag -Docker 'unused' -ProjectRoot 'Z:/does-not-exist' -Settings @{SEC_RAG_EMBEDDING_PROVIDER='external'} -RunDirectory 'unused' -Inspect { @() } -BuildRag {
    param($service)
    if ($service -ne 'chroma') { $script:wrongExternalRagService = $true }
    $script:externalRagStarted = $true
}
if (!$script:externalRagStarted -or $script:wrongExternalRagService) { throw '外部RAG必须只启动Chroma' }
Write-Host 'PASS 14：RAG禁用不准备模型'

# 临时源码与状态只写入随机测试目录，不修改正式构建状态或启动容器。
$ragFixture=Join-Path ([IO.Path]::GetTempPath()) ('exchange-rag-startup-'+[Guid]::NewGuid().ToString('N'))
$ragSource=Join-Path $ragFixture 'build/rag/embedding'
$ragState=Join-Path $ragFixture 'build/.local-run'
[void][IO.Directory]::CreateDirectory($ragSource)
[void][IO.Directory]::CreateDirectory($ragState)
try {
    [IO.File]::WriteAllText((Join-Path $ragSource 'app.py'),'test-only')
    $ragCounts=[pscustomobject]@{Builds=0}
    $ragHealthy=@('chroma','embedding')|ForEach-Object{[pscustomobject]@{Name=$_;State='running';Health='healthy'}}
    Ensure-ExchangeRag -Docker 'unused' -ProjectRoot $ragFixture -Settings @{} -RunDirectory $ragState -Inspect {return $ragHealthy} -BuildRag {$ragCounts.Builds++}
    Ensure-ExchangeRag -Docker 'unused' -ProjectRoot $ragFixture -Settings @{} -RunDirectory $ragState -Inspect {return $ragHealthy} -BuildRag {$ragCounts.Builds++}
    Assert-Startup ($ragCounts.Builds -eq 1) 'RAG相同源码和健康容器应复用构建'
    Write-Host 'PASS 15：RAG构建与内容缓存'
    $warnings=@()
    Ensure-ExchangeRag -Docker 'unused' -ProjectRoot $ragFixture -Settings @{} -RunDirectory $ragState -Inspect {return @()} -BuildRag {throw '模拟下载失败'} -WarningVariable warnings
    Assert-Startup ($warnings.Count -eq 1) 'RAG失败必须只警告，不抛出阻断交易的异常'
    Write-Host 'PASS 16：RAG失败仅降级'
} finally {
    [IO.File]::Delete((Join-Path $ragSource 'app.py'))
    [IO.File]::Delete((Join-Path $ragState 'rag-build-state.json'))
    [IO.Directory]::Delete($ragSource)
    [IO.Directory]::Delete((Join-Path $ragFixture 'build/rag'))
    [IO.Directory]::Delete($ragState)
    [IO.Directory]::Delete((Join-Path $ragFixture 'build'))
    [IO.Directory]::Delete($ragFixture)
}
# 验证四种启动组合，不连接 Docker；关闭某组即不执行该组回调。
$optional = [System.Collections.Generic.List[string]]::new()
$startMonitoring = { $optional.Add('monitoring') }
$startRag = { $optional.Add('rag') }
Start-ExchangeOptionalGroups -StartMonitoring $startMonitoring -StartRag $startRag
Assert-Startup ($optional.Count -eq 0) '默认模式不启动可选组'
Start-ExchangeOptionalGroups -Monitoring -StartMonitoring $startMonitoring -StartRag $startRag
Assert-Startup (($optional -join ',') -eq 'monitoring') '监控模式不得启动 RAG'
$optional.Clear()
Start-ExchangeOptionalGroups -Rag -StartMonitoring $startMonitoring -StartRag $startRag
Assert-Startup (($optional -join ',') -eq 'rag') 'RAG 模式不得启动监控'
$optional.Clear()
Start-ExchangeOptionalGroups -Full -StartMonitoring $startMonitoring -StartRag $startRag
Assert-Startup (($optional -join ',') -eq 'monitoring,rag') '完整模式应启动两个可选组'
Write-Host 'PASS 17：可选组的四种启动组合'

# 模拟监控失败，仍须尝试 RAG；核心组判定忽略故障的监控容器。
$optional.Clear()
Start-ExchangeOptionalGroups -Full -StartMonitoring { throw '模拟监控故障' } -StartRag $startRag
Assert-Startup (($optional -join ',') -eq 'rag') '监控失败不能阻止 RAG'
Assert-Startup (Test-ExchangeContainersReady ($healthy + @([pscustomobject]@{Name='grafana';State='exited';Health='not-ready'}))) '监控失败不能阻断核心健康检查'
Write-Host 'PASS 18：可选组故障隔离'
Write-Host '一键启动脚本隔离测试全部通过（18 组）。'
