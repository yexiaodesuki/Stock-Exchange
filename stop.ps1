# 一键停止本项目脚本记录的 Java 应用：身份匹配才终止，保留 Docker、数据卷和日志。
#requires -Version 5.1
param([ValidateRange(1, 60)][int]$StopTimeoutSeconds = 10)

# 把启动记录限制在本项目七个固定 JAR 内，不信任记录中任意进程或外部路径。
function Test-ExchangeStopRecord {
    param($Record, [string]$ProjectRoot)
    $modules = @('config', 'trading-engine', 'trading-sequencer', 'quotation', 'trading-api', 'push', 'ui')
    if ($null -eq $Record -or $Record.Name -notin $modules) { return $false }
    $number = 0
    $ticks = [long]0
    if (![int]::TryParse([string]$Record.ProcessId, [ref]$number) -or $number -le 0) { return $false }
    if (![long]::TryParse([string]$Record.StartTicks, [ref]$ticks) -or $ticks -le 0) { return $false }
    try {
        $expected = [IO.Path]::GetFullPath((Join-Path $ProjectRoot ($Record.Name + '/target/' + $Record.Name + '.jar')))
        return [IO.Path]::GetFullPath([string]$Record.Jar) -eq $expected
    } catch { return $false }
}

# 合并所有历史记录并去重；缺失目录视为没有记录，损坏记录不猜测、更不据此终止进程。
function Get-ExchangeStopRecords {
    param([string]$ProjectRoot)
    $directory = Join-Path $ProjectRoot 'build/.local-run'
    if (!(Test-Path -LiteralPath $directory)) { return }
    $records = foreach ($run in (Get-ChildItem -LiteralPath $directory -Directory)) {
        $path = Join-Path $run.FullName 'processes.json'
        if (!(Test-Path -LiteralPath $path)) { continue }
        try { $rows = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json }
        catch { Write-Warning ('跳过无法解析的启动记录：' + $path); continue }
        foreach ($row in $rows) {
            if (Test-ExchangeStopRecord $row $ProjectRoot) { $row }
            else { Write-Warning ('跳过无效启动记录：' + $path) }
        }
    }
    $records | Group-Object { [string]$_.ProcessId + ':' + [string]$_.StartTicks } | ForEach-Object { $_.Group[0] }
}

# 同时检查 PID、启动时间、Java 名称和精确的 -jar 参数，防止 PID 重用或误伤 IDEA。
function Test-ExchangeStopIdentity {
    param($Record, $Process, $Owner, [string]$ProjectRoot)
    if (!(Test-ExchangeStopRecord $Record $ProjectRoot) -or $null -eq $Process -or $null -eq $Owner) { return $false }
    if ($Process.Id -ne [int]$Record.ProcessId -or $Owner.ProcessId -ne [int]$Record.ProcessId) { return $false }
    if ($Owner.Name -notin @('java.exe', 'javaw.exe')) { return $false }
    if ($Process.StartTime.Ticks -ne [long]$Record.StartTicks) { return $false }
    $pattern = '(?i)(?:^|\s)-jar\s+"?' + [regex]::Escape([string]$Record.Jar) + '"?(?:\s|$)'
    return $Owner.CommandLine -match $pattern
}

# 固定依赖逆序停止；注入查询与终止函数便于测试，整个流程不调用 Docker 或修改业务数据。
function Stop-ExchangeRecordedApplications {
    param([array]$Records, [string]$ProjectRoot, [scriptblock]$FindProcess,
        [scriptblock]$FindOwner, [scriptblock]$Terminate)
    $stopped = 0
    $absent = 0
    $protected = 0
    $seen = @{}
    foreach ($name in @('ui', 'push', 'trading-api', 'quotation', 'trading-sequencer', 'trading-engine', 'config')) {
        foreach ($record in @($Records | Where-Object { $_.Name -eq $name })) {
            if (!(Test-ExchangeStopRecord $record $ProjectRoot)) {
                $protected++
                Write-Warning '跳过无效进程记录，不执行停止。'
                continue
            }
            $key = [string]$record.ProcessId + ':' + [string]$record.StartTicks
            if ($seen.ContainsKey($key)) { continue }
            $seen[$key] = $true
            $process = & $FindProcess ([int]$record.ProcessId)
            if ($null -eq $process) { $absent++; continue }
            $owner = & $FindOwner ([int]$record.ProcessId)
            if (!(Test-ExchangeStopIdentity $record $process $owner $ProjectRoot)) {
                $protected++
                Write-Warning ('跳过身份不符或已被重用的 PID：' + $record.ProcessId)
                continue
            }
            & $Terminate $process
            $stopped++
            Write-Host ('已停止 ' + $record.Name + '，PID=' + $record.ProcessId)
        }
    }
    Write-Host ('完成：停止 ' + $stopped + '，此前已退出 ' + $absent + '，保护跳过 ' + $protected + '。不会停止 Docker 容器。')
    return [pscustomobject]@{ Stopped = $stopped; AlreadyStopped = $absent; Protected = $protected }
}

# 入口只使用启动器记录；记录不存在时安全退出，不扫描并批量关闭所有 Java 进程。
function Stop-ExchangeProject {
    param([string]$ProjectRoot, [int]$TimeoutSeconds = 10)
    $ErrorActionPreference = 'Stop'
    $records = @(Get-ExchangeStopRecords $ProjectRoot)
    if ($records.Count -eq 0) { Write-Host '没有可用的脚本启动记录，无需停止；Docker 不受影响。'; return }
    [void](Stop-ExchangeRecordedApplications $records $ProjectRoot -FindProcess {
        param($number) Get-Process -Id $number -ErrorAction SilentlyContinue
    } -FindOwner {
        param($number) Get-CimInstance Win32_Process -Filter ('ProcessId=' + $number)
    } -Terminate {
        param($process)
        # Windows 的进程终止不保证 JVM shutdown hook，不能宣称这是优雅停机。
        Stop-Process -InputObject $process -Force -ErrorAction Stop
        if (!$process.WaitForExit($TimeoutSeconds * 1000)) { throw ('等待进程退出超时：' + $process.Id) }
    })
}

# 点导入只定义函数供测试使用，直接执行才会终止匹配的应用。
if ($MyInvocation.InvocationName -ne '.') {
    try { Stop-ExchangeProject -ProjectRoot $PSScriptRoot -TimeoutSeconds $StopTimeoutSeconds }
    catch { Write-Error $_.Exception.Message; exit 1 }
}
