# 页面自动验收：纯逻辑及隔离六服务回归；-Gateway 增加真实临时 Nginx 的浏览器交易验证。
param([switch]$UiOnly, [switch]$Gateway)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
& node --test (Join-Path $projectRoot 'ui/src/test/js/domain.test.mjs')
if($LASTEXITCODE -ne 0){throw '证券 UI 纯逻辑测试失败。'}
if(!(Test-Path -LiteralPath (Join-Path $projectRoot 'ui/node_modules/playwright'))){throw '请先 npm --prefix ui ci 安装浏览器测试依赖（Windows 默认使用已安装 Edge）。'}
$previous=[Environment]::GetEnvironmentVariable('SECURITIES_TEST_BROWSER','Process')
$previousGateway=[Environment]::GetEnvironmentVariable('SECURITIES_TEST_GATEWAY','Process')
$ragSaved=@{}
foreach($key in @('SECURITIES_RAG_LIVE_TESTS','SECURITIES_TEST_CHROMA_URL','SECURITIES_TEST_EMBEDDING_URL')){$ragSaved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
try{
    # 模型服务可用时验证真实向量；交易进程回归单独关闭RAG，避免混用正式知识集合。
    $env:SECURITIES_RAG_LIVE_TESTS='false'
    if(!$UiOnly){
        $ports=@{SEC_CHROMA_PORT='18080';SEC_EMBEDDING_PORT='18081'}
        Get-Content -LiteralPath (Join-Path $projectRoot 'build/.env.securities') | ForEach-Object {if($_ -match '^(SEC_CHROMA_PORT|SEC_EMBEDDING_PORT)=(\d+)$'){$ports[$matches[1]]=$matches[2]}}
        $env:SECURITIES_TEST_CHROMA_URL='http://127.0.0.1:'+$ports['SEC_CHROMA_PORT']
        $env:SECURITIES_TEST_EMBEDDING_URL='http://127.0.0.1:'+$ports['SEC_EMBEDDING_PORT']
        try{$info=Invoke-RestMethod ($env:SECURITIES_TEST_EMBEDDING_URL+'/info') -TimeoutSec 2;Invoke-RestMethod ($env:SECURITIES_TEST_CHROMA_URL+'/api/v2/heartbeat') -TimeoutSec 2|Out-Null;if($info.state -eq 'READY' -and $info.dimension -eq 512){$env:SECURITIES_RAG_LIVE_TESTS='true';Write-Host '真实本地RAG集成已启用：随机知识集合，假生成器，不调用收费接口。'}}catch{Write-Warning 'RAG服务未就绪，跳过真实向量集成；原文降级和交易回归仍执行。'}
        if($env:SECURITIES_RAG_LIVE_TESTS -eq 'true'){Get-Content -LiteralPath (Join-Path $projectRoot 'build/rag/embedding/test_app.py') -Raw | & docker exec -i embedding python -;if($LASTEXITCODE -ne 0){throw '嵌入服务纯逻辑测试失败。'}}
    }
    $env:SECURITIES_TEST_BROWSER='true'
    if($Gateway){$env:SECURITIES_TEST_GATEWAY='true'}
    if($UiOnly){ & (Join-Path $PSScriptRoot 'test-phase2.ps1') -TestPattern 'SecuritiesSerializationTest,SecuritiesWebTest,SecuritiesUiProcessTest' }
    else{ & (Join-Path $PSScriptRoot 'test-phase2.ps1') }
}
finally{[Environment]::SetEnvironmentVariable('SECURITIES_TEST_BROWSER',$previous,'Process');[Environment]::SetEnvironmentVariable('SECURITIES_TEST_GATEWAY',$previousGateway,'Process');foreach($key in $ragSaved.Keys){[Environment]::SetEnvironmentVariable($key,$ragSaved[$key],'Process')}}
