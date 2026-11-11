# 第三阶段验收复用隔离库运行器；运行全部回归和五服务真实进程/API/WebSocket 联调。
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'test-phase2.ps1')
