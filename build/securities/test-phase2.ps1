# 第二阶段隔离验收：临时库复用正式 Flyway 基线；先打包，再运行单元、数据库与真实进程恢复测试。
# 可显式选择测试类定位问题；默认仍完整回归，局部结果不能冒充全量验收。
param([string]$TestPattern='')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$envPath=Join-Path $projectRoot 'build/.env.securities'
$settings=@{}
Get-Content -LiteralPath $envPath | ForEach-Object {
    # 仅读取键值，绝不执行配置内容或输出凭据。
    if($_ -match '^(SEC_[A-Z_]+)=(.*)$'){ $settings[$matches[1]]=$matches[2] }
}
if(!$settings['SEC_MYSQL_PASSWORD']){throw '请先填写 build/.env.securities 并启动 build/docker-compose.yml。'}
$composeArgs=@('compose','--env-file',$envPath,'-p','stock-exchange-securities','-f',(Join-Path $projectRoot 'build/docker-compose.yml'))
$database='exchange_securities_test_'+[Guid]::NewGuid().ToString('N')
$saved=@{}
foreach($key in @('SECURITIES_PHASE2_DB_URL','SECURITIES_DB_PASSWORD','SECURITIES_TEST_BOOTSTRAP','SECURITIES_TEST_ROOT','SECURITIES_DB_TESTS','EXCHANGE_MONITORING_LIVE_TESTS','SECURITIES_TEST_REDIS_URI','SECURITIES_TEST_PWSH')) {
    $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')
}
$created=$false
$configProcess=$null
$previousConfigServer=[Environment]::GetEnvironmentVariable('CONFIG_SERVER','Process')
try {
    $created=$true
    Get-Content -LiteralPath (Join-Path $PSScriptRoot 'test-database.sh') -Raw | & docker @composeArgs exec -T mysql bash -c 'tr -d "\r" | bash -s -- "$1" "$2"' -- create $database
    if($LASTEXITCODE -ne 0){throw '创建临时测试库失败。'}
    # 空库由 Java 证券目录入口自动迁移，不再依赖额外任务容器。
    $env:SECURITIES_PHASE2_DB_URL="jdbc:mysql://127.0.0.1:$($settings['SEC_MYSQL_PORT'])/${database}?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8"
    $env:SECURITIES_DB_PASSWORD=$settings['SEC_MYSQL_PASSWORD']
    $env:SECURITIES_TEST_BOOTSTRAP="127.0.0.1:$($settings['SEC_KAFKA_PORT'])"
    $env:SECURITIES_TEST_ROOT=$projectRoot
    $env:SECURITIES_TEST_REDIS_URI="redis://127.0.0.1:$($settings['SEC_REDIS_PORT'])/0"
    $pwsh=Get-Command pwsh -ErrorAction SilentlyContinue
    if($pwsh){$env:SECURITIES_TEST_PWSH=$pwsh.Source}
    $env:SECURITIES_DB_TESTS='false'; $env:EXCHANGE_MONITORING_LIVE_TESTS='false'
    & mvn -f (Join-Path $projectRoot 'build/pom.xml') clean package '-DskipTests'
    if($LASTEXITCODE -ne 0){throw '证券应用打包失败。'}
    # 测试也必须经过真正的配置服务，客户端不可用绕过开关替代。
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$listener.Start();$configPort=$listener.LocalEndpoint.Port;$listener.Stop()
    $configLog=Join-Path ([IO.Path]::GetTempPath()) ('securities-config-'+[Guid]::NewGuid().ToString('N'))
    $privateEnvironment=@{}
    foreach($item in (Get-ChildItem Env: | Where-Object {$_.Name -match '^SEC' -and $_.Name -match 'PASSWORD|AUTH_KEY'})){$privateEnvironment[$item.Name]=$item.Value;[Environment]::SetEnvironmentVariable($item.Name,$null,'Process')}
    $repoBefore=[Environment]::GetEnvironmentVariable('CONFIG_REPO','Process')
    try{
        $env:CONFIG_REPO='file:'+((Join-Path $projectRoot 'config-repo').Replace('\','/'))+'/'
        $configJar=Join-Path $projectRoot 'config/target/config.jar'
        $configProcess=Start-Process java -ArgumentList @('-Xmx160m','-Dfile.encoding=UTF-8','-jar',('"'+$configJar+'"'),"--server.port=$configPort",'--management.server.port=0','--logging.level.root=WARN') -WindowStyle Hidden -PassThru -RedirectStandardOutput ($configLog+'.out.log') -RedirectStandardError ($configLog+'.err.log')
    }finally{
        foreach($key in $privateEnvironment.Keys){[Environment]::SetEnvironmentVariable($key,$privateEnvironment[$key],'Process')}
        [Environment]::SetEnvironmentVariable('CONFIG_REPO',$repoBefore,'Process')
    }
    $env:CONFIG_SERVER="http://127.0.0.1:$configPort"
    $configReady=$false
    foreach($attempt in 1..60){try{$reply=Invoke-WebRequest -UseBasicParsing -Uri "$($env:CONFIG_SERVER)/securities-engine/default" -TimeoutSec 2;if($reply.StatusCode -eq 200){$configReady=$true;break}}catch{Start-Sleep -Milliseconds 500}}
    if(!$configReady){throw "测试配置中心未启动，日志位于 $configLog"}
    $verifyArgs=@('-f',(Join-Path $projectRoot 'build/pom.xml'),'verify','-DargLine=-Dfile.encoding=UTF-8')
    if($TestPattern){$verifyArgs+=@("-Dtest=$TestPattern",'-Dsurefire.failIfNoSpecifiedTests=false')}
    & mvn @verifyArgs
    if($LASTEXITCODE -ne 0){throw '证券回归失败，请检查 surefire-reports 和输出的进程日志目录。'}
} finally {
    if($configProcess -and !$configProcess.HasExited){$configProcess.Kill();$configProcess.WaitForExit()}
    [Environment]::SetEnvironmentVariable('CONFIG_SERVER',$previousConfigServer,'Process')
    if($created){
        # 精确删除本次随机测试库和权限；失败不扩大清理范围。
        Get-Content -LiteralPath (Join-Path $PSScriptRoot 'test-database.sh') -Raw | & docker @composeArgs exec -T mysql bash -c 'tr -d "\r" | bash -s -- "$1" "$2"' -- drop $database
        if($LASTEXITCODE -ne 0){Write-Warning "临时测试库清理失败：$database"}
    }
    foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
}
