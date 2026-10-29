# 页面自动验收：纯逻辑及隔离六服务回归；-Gateway 增加真实临时 Nginx 的浏览器交易验证。
param([switch]$UiOnly, [switch]$Gateway)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
& node --test (Join-Path $projectRoot 'ui/src/test/js/domain.test.mjs')
if($LASTEXITCODE -ne 0){throw '证券 UI 纯逻辑测试失败。'}
if(!(Test-Path -LiteralPath (Join-Path $projectRoot 'ui/node_modules/playwright'))){throw '请先 npm --prefix ui ci 安装浏览器测试依赖（Windows 默认使用已安装 Edge）。'}
$previous=[Environment]::GetEnvironmentVariable('SECURITIES_TEST_BROWSER','Process')
$previousGateway=[Environment]::GetEnvironmentVariable('SECURITIES_TEST_GATEWAY','Process')
try{
    $env:SECURITIES_TEST_BROWSER='true'
    if($Gateway){$env:SECURITIES_TEST_GATEWAY='true'}
    if($UiOnly){ & (Join-Path $PSScriptRoot 'test-phase2.ps1') -TestPattern 'SecuritiesSerializationTest,SecuritiesWebTest,SecuritiesUiProcessTest' }
    else{ & (Join-Path $PSScriptRoot 'test-phase2.ps1') }
}
finally{[Environment]::SetEnvironmentVariable('SECURITIES_TEST_BROWSER',$previous,'Process');[Environment]::SetEnvironmentVariable('SECURITIES_TEST_GATEWAY',$previousGateway,'Process')}
