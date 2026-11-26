# 当前版本验收：默认不连接容器与模型，联调需显式启用。
param([switch]$Offline, [switch]$Integration)
$ErrorActionPreference = 'Stop'
if ($Offline -and $Integration) { throw 'Offline 仅用于无容器回归。' }
Push-Location -LiteralPath (Join-Path $PSScriptRoot '../..')
try {
    if ($Integration) {
        # 即使开发环境选了本地聊天模型，联调也只检查降级与模拟结果。
        $previousProvider = [Environment]::GetEnvironmentVariable('SEC_ASSISTANT_PROVIDER', 'Process')
        try {
            $env:SEC_ASSISTANT_PROVIDER = 'external'
            & (Join-Path $PSScriptRoot 'test-phase4.ps1') -Gateway
        } finally {
            [Environment]::SetEnvironmentVariable('SEC_ASSISTANT_PROVIDER', $previousProvider, 'Process')
        }
    } else {
        # 禁用继承的实服务测试开关，结束后恢复当前进程环境。
        $saved = @{}
        $pattern = '^(CONFIG_SERVER|EXCHANGE_MONITORING_LIVE_TESTS|SECURITIES_(DB_TESTS|PHASE2_DB_URL|RAG_LIVE_TESTS|TEST_.+))$'
        foreach ($entry in (Get-ChildItem Env: | Where-Object Name -Match $pattern)) {
            $saved[$entry.Name] = $entry.Value
            [Environment]::SetEnvironmentVariable($entry.Name, $null, 'Process')
        }
        try {
            $env:SECURITIES_TEST_PWSH = (Get-Command pwsh -ErrorAction Stop).Source
            $arguments = @('-B', '-ntp', '-f', 'build/pom.xml', 'clean', 'test')
            if ($Offline) { $arguments = @('-o') + $arguments }
            & mvn @arguments
            if ($LASTEXITCODE -ne 0) { throw 'Java 回归失败。' }
        } finally {
            [Environment]::SetEnvironmentVariable('SECURITIES_TEST_PWSH', $null, 'Process')
            foreach ($key in $saved.Keys) {
                [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process')
            }
        }
        & node --test ui/src/test/js/domain.test.mjs
        if ($LASTEXITCODE -ne 0) { throw '前端逻辑回归失败。' }
    }
    & (Join-Path $PSScriptRoot 'test-startup.ps1')
    & (Join-Path $PSScriptRoot 'test-stop.ps1')
    $total = $skipped = $failed = 0
    $integrationRequired = @('SecuritiesUiProcessTest', 'SecuritiesProcessTest', 'DepositIntegrationTest')
    foreach ($module in Get-ChildItem -Directory) {
        $directory = Join-Path $module.FullName 'target/surefire-reports'
        if (!(Test-Path -LiteralPath $directory)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml') {
            $suite = ([xml](Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8)).testsuite
            $total += [int]$suite.tests
            $skipped += [int]$suite.skipped
            $failed += [int]$suite.failures + [int]$suite.errors
            if ([int]$suite.tests -gt [int]$suite.skipped) {
                $integrationRequired = @($integrationRequired | Where-Object { $_ -ne ($suite.name -split '\.')[-1] })
            }
            if ([int]$suite.skipped -gt 0) { Write-Host ("SKIPPED {0}: {1}" -f $suite.skipped, $suite.name) }
        }
    }
    Write-Host ("Java: total={0}, passed={1}, skipped={2}, failed={3}" -f $total, ($total-$skipped-$failed), $skipped, $failed)
    if ($total -le $skipped -or $failed) { throw '未取得有效的 Java 回归结果。' }
    if ($Integration -and $integrationRequired.Count) { throw ('缺少必跑联调：' + ($integrationRequired -join ', ')) }
} finally {
    Pop-Location
}
