# 停止脚本隔离测试：模拟进程、历史记录与 PID 重用，不停止真实应用或容器。
#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $projectRoot 'stop.ps1')

# 断言失败返回非零，测试不依赖额外测试框架。
function Assert-Stop {
    param([bool]$Condition, [string]$Message)
    if (!$Condition) { throw $Message }
}

# 构造与启动脚本一致的记录，不创建实际进程。
function New-StopTestRecord {
    param([string]$Name, [int]$Number, [string]$Root = $projectRoot)
    return [pscustomobject]@{ Name = $Name; ProcessId = $Number; StartTicks = [DateTime]::Today.Ticks
        Jar = Join-Path $Root ($Name + '/target/' + $Name + '.jar') }
}

$record = New-StopTestRecord 'config' 100
$process = [pscustomobject]@{ Id = 100; StartTime = [DateTime]::Today }
$owner = [pscustomobject]@{ ProcessId = 100; Name = 'java.exe'; CommandLine = 'java -jar "' + $record.Jar + '"' }
Assert-Stop (Test-ExchangeStopIdentity $record $process $owner $projectRoot) '正确进程身份应通过'
$owner.CommandLine = 'java -jar "D:\other\config.jar"'
Assert-Stop (!(Test-ExchangeStopIdentity $record $process $owner $projectRoot)) '其他项目不可停止'
$owner.CommandLine = 'java com.itranswarp.exchange.ConfigApplication'
Assert-Stop (!(Test-ExchangeStopIdentity $record $process $owner $projectRoot)) '不能误伤 IDEA'
Write-Host 'PASS 1：完整 JAR 路径与 IDEA 保护'

$owner.CommandLine = 'java -jar "' + $record.Jar + '"'
$process.StartTime = [DateTime]::Today.AddSeconds(1)
Assert-Stop (!(Test-ExchangeStopIdentity $record $process $owner $projectRoot)) 'PID 重用应拒绝'
$process.StartTime = [DateTime]::Today
$owner.Name = 'other.exe'
Assert-Stop (!(Test-ExchangeStopIdentity $record $process $owner $projectRoot)) '非 Java 进程应拒绝'
$owner.Name = 'java.exe'
$owner.ProcessId = 101
Assert-Stop (!(Test-ExchangeStopIdentity $record $process $owner $projectRoot)) '查询进程必须与记录同 PID'
Write-Host 'PASS 2：启动时间、进程类型和 PID 校验'

$outside = New-StopTestRecord 'ui' 200
$outside.Jar = 'D:\other\ui.jar'
Assert-Stop (!(Test-ExchangeStopRecord $outside $projectRoot)) '记录不得指向外部路径'
$outside = New-StopTestRecord 'unknown' 200
Assert-Stop (!(Test-ExchangeStopRecord $outside $projectRoot)) '未知应用不能参与停止'
$outside = New-StopTestRecord 'ui' 0
Assert-Stop (!(Test-ExchangeStopRecord $outside $projectRoot)) '无效 PID 必须拒绝'
Write-Host 'PASS 3：记录范围保护'

$names = @('config','trading-engine','trading-sequencer','quotation','trading-api','push','ui')
$records = @(for ($i = 0; $i -lt $names.Count; $i++) { New-StopTestRecord $names[$i] (300 + $i) })
$active = @{}
foreach ($item in $records) { $active[[int]$item.ProcessId] = $item }
$trace = New-Object 'System.Collections.Generic.List[string]'
$find = {
    param($number)
    if ($active.ContainsKey($number)) { return [pscustomobject]@{ Id = $number; StartTime = [DateTime]::Today } }
    return $null
}
$inspect = {
    param($number)
    return [pscustomobject]@{ ProcessId = $number; Name = 'java.exe'; CommandLine = 'java -jar "' + $active[$number].Jar + '"' }
}
$terminate = {
    param($value)
    [void]$trace.Add($active[$value.Id].Name)
    $active.Remove($value.Id)
}
$result = Stop-ExchangeRecordedApplications (@($records) + @($records[0])) $projectRoot -FindProcess $find -FindOwner $inspect -Terminate $terminate
Assert-Stop (($trace.ToArray() -join ',') -eq 'ui,push,trading-api,quotation,trading-sequencer,trading-engine,config') '停止顺序不正确'
Assert-Stop ($result.Stopped -eq 7 -and $trace.Count -eq 7) '重复记录不应重复停止'
Write-Host 'PASS 4：依赖逆序与记录去重'

$result = Stop-ExchangeRecordedApplications $records $projectRoot -FindProcess $find -FindOwner $inspect -Terminate { throw '重复停止不应再次终止' }
Assert-Stop ($result.Stopped -eq 0 -and $result.AlreadyStopped -eq 7) '重复运行应安全跳过'
Write-Host 'PASS 5：重复执行幂等'

$result = Stop-ExchangeRecordedApplications @($record) $projectRoot -FindProcess { return $process } -FindOwner { return $owner } -Terminate { throw '身份不符不得调用终止' }
Assert-Stop ($result.Protected -eq 1 -and $result.Stopped -eq 0) '身份不符必须保护'
Write-Host 'PASS 6：拒绝不符记录且不调用终止'

$fixture = Join-Path ([IO.Path]::GetTempPath()) ('exchange-stop-test-' + [Guid]::NewGuid().ToString('N'))
$runA = Join-Path $fixture 'build/.local-run/a'
$runB = Join-Path $fixture 'build/.local-run/b'
[void][IO.Directory]::CreateDirectory($runA)
[void][IO.Directory]::CreateDirectory($runB)
try {
    @((New-StopTestRecord 'config' 500 $fixture), (New-StopTestRecord 'trading-engine' 502 $fixture)) | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runA 'processes.json') -Encoding UTF8
    @(New-StopTestRecord 'ui' 501 $fixture) | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runB 'processes.json') -Encoding UTF8
    $combined = @(Get-ExchangeStopRecords $fixture)
    Assert-Stop ($combined.Count -eq 3) '不能只读最近一次不完整记录，必须兼容 JSON 数组'
    [IO.File]::WriteAllText((Join-Path $runB 'processes.json'), '{broken')
    $combined = @(Get-ExchangeStopRecords $fixture)
    Assert-Stop ($combined.Count -eq 2 -and 'config' -in $combined.Name) '损坏记录应跳过，不丢失其他有效记录'
} finally {
    [IO.File]::Delete((Join-Path $runA 'processes.json'))
    [IO.File]::Delete((Join-Path $runB 'processes.json'))
    [IO.Directory]::Delete($runA)
    [IO.Directory]::Delete($runB)
    [IO.Directory]::Delete((Join-Path $fixture 'build/.local-run'))
    [IO.Directory]::Delete((Join-Path $fixture 'build'))
    [IO.Directory]::Delete($fixture)
}
Write-Host 'PASS 7：历史合并和损坏记录处理'

$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $projectRoot 'stop.ps1'), [ref]$tokens, [ref]$errors)
Assert-Stop ($errors.Count -eq 0) '脚本语法错误'
$forbidden = @($ast.FindAll({ param($node)
    $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -in @('docker','taskkill','Stop-Service','Restart-Service')
}, $true))
Assert-Stop ($forbidden.Count -eq 0) '停止脚本不能包含 Docker 或全局停止命令'
Write-Host 'PASS 8：不含 Docker 或全局停止命令'
Write-Host '停止脚本隔离测试全部通过（8 组）。'
