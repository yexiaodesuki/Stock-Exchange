# Java 全量回归入口：从被忽略的证券配置读取凭据，只开启证券库集成测试，不启动业务应用。
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$envPath = Join-Path $projectRoot 'build/.env.securities'
if (!(Test-Path -LiteralPath $envPath)) { throw '请先填写 build/.env.securities 并启动 build/docker-compose.yml。' }
$settings = @{}
Get-Content -LiteralPath $envPath | ForEach-Object {
    # 仅解析键值，不执行配置文本，不输出密码。
    if ($_ -match '^(SEC_[A-Z_]+)=(.*)$') { $settings[$matches[1]] = $matches[2] }
}
if (!$settings['SEC_MYSQL_PASSWORD'] -or $settings['SEC_MYSQL_PORT'] -notmatch '^\d{1,5}$') {
    throw '证券数据库配置缺失。'
}
$saved = @{}
foreach ($key in @('SECURITIES_DB_TESTS','SECURITIES_JDBC_URL','SECURITIES_DB_PASSWORD','EXCHANGE_MONITORING_LIVE_TESTS')) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    $env:SECURITIES_DB_TESTS = 'true'
    $env:SECURITIES_JDBC_URL = "jdbc:mysql://127.0.0.1:$($settings['SEC_MYSQL_PORT'])/exchange_securities?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8"
    $env:SECURITIES_DB_PASSWORD = $settings['SEC_MYSQL_PASSWORD']
    # 原监控联调默认连接旧环境，此次回归明确不启用，以免误访问旧服务。
    $env:EXCHANGE_MONITORING_LIVE_TESTS = 'false'
    & mvn -f (Join-Path $projectRoot 'build/pom.xml') verify '-DargLine=-Dfile.encoding=UTF-8'
    if ($LASTEXITCODE -ne 0) { throw 'Java 单元/集成回归失败，请检查各模块 surefire-reports。' }
} finally {
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
}
