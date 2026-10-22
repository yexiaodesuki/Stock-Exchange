<!-- 第一阶段引擎监控说明：保留本地修改；第二阶段应用与依赖扩展在独立说明中记录。 -->
# 交易引擎监控（第一阶段）

第二阶段的七应用端口、依赖缓存、消息与 Push 指标见 [系统与依赖监控](system-monitoring.md)。本页保留第一阶段说明。

## 范围和流程

`引擎执行/恢复/快照 → Micrometer 内存指标 → Actuator :18002 → Prometheus → Grafana`

本阶段只监控 trading-engine，不修改交易协议、资产、数据库结构、消费位置或恢复算法。不包含分布式链路追踪、日志平台、通知告警、可靠输出重试、资金对账以及其他业务应用的监控。

## 启动

1. 日常方式开启原有 MySQL、Redis、Kafka；不要删容器、数据或重新执行 schema.sql。
2. IDEA 重新加载 Maven；启动或重启 ConfigApplication，然后重启 TradingEngineApplication，让新的集中配置生效。新配置不会自动应用到已经启动的引擎。
3. 浏览器打开 `http://localhost:18002/actuator/health/readiness`：恢复中或输出线程退出时为 HTTP 503，完整就绪时为 200。`/actuator/health/liveness` 只证明 Spring 进程存活，不代表可以交易。
4. 打开 `                                                                  `，应出现 `exchange_engine_ready`、`exchange_engine_applied_sequence` 等指标。
5. 在项目根目录 PowerShell 执行：

```powershell
cd 'D:\Stock Exchange'
# 密码只保留在当前终端环境；不要提交到 Git。
$credential = Read-Host '设置 Grafana 管理员密码' -AsSecureString
$env:GRAFANA_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $credential).Password
docker compose -f build/docker-compose.monitoring.yml up -d
docker compose -f build/docker-compose.monitoring.yml ps
```

首次部署需要下载镜像。Grafana 会把初始账号写入自己的命名卷；之后修改环境变量不会自动重置已有账号密码。环境变量不是生产级密钥管理，Docker 管理员可读取容器环境。

6. 打开 `http://localhost:9090/targets`，`trading-engine` 应为 UP。Prometheus 容器经 `host.docker.internal:18002` 访问 IDEA 中运行的引擎，而非经容器里的 localhost。
7. 打开 `http://localhost:3000`，使用 admin 和刚才设置的密码，进入 **Stock Exchange → Stock Exchange - 交易引擎**。数据源和仪表盘自动导入，等待至少两个抓取周期查看速率曲线。

停止监控可执行 `docker compose -f build/docker-compose.monitoring.yml stop`，不会停止业务容器。不要使用 `down -v`，它会删除监控历史和 Grafana 设置。

## 安全与地址

管理端口默认 18002，业务端口仍是 8002。只暴露 health、prometheus，不暴露 env、heapdump、shutdown。指标不包含用户 ID、余额、订单 ID 或事件载荷。

为了 Windows Docker Desktop 访问宿主机，管理端口监听 0.0.0.0，**未配置认证**。仅用于受信任本地开发环境，使用 Windows 防火墙阻止不必要的局域网/公网访问；不得向公网映射。生产环境必须增加网络隔离和认证。Prometheus/Grafana 界面仅发布到宿主机 127.0.0.1。

端口被占用时可设置引擎 `ENGINE_MANAGEMENT_PORT`；同时修改 prometheus.yml 的目标端口。若设 `ENGINE_MANAGEMENT_ADDRESS=127.0.0.1`，需验证 Docker 是否能访问，不应默认能访问。引擎目标 DOWN 优先检查应用、集中配置加载、18002 监听、防火墙和 Docker 到宿主机路径。

## 指标含义

| 指标（Prometheus 名称） | 含义与边界 |
| --- | --- |
| `up` | Prometheus 最近一次抓取成功；不是交易就绪 |
| `exchange_engine_ready` | 恢复完成且预期输出线程都存活 |
| `exchange_engine_state{state}` | RECOVERING、READY、FAILED、STOPPED 的单值状态指示 |
| `exchange_engine_applied_sequence` | 完整事件执行或有效快照安装后的位置 |
| `exchange_recovery_target_sequence` | 启动时确定的历史上界，运行后不会随新事件增长 |
| `exchange_events_total{mode,result}` | live/replay 分开统计 applied、duplicate、failure；余额不足等业务拒绝仍算已应用事件 |
| `exchange_event_duration_seconds_*` | 事件处理耗时；缺口补读外层不重复计数，仅记录实际子事件 |
| `exchange_recovery_completed_total{result}` | 启动恢复成功/失败；失败后进程可能很快退出，Prometheus 未必来得及抓取 |
| `exchange_recovery_duration_seconds_*` | 恢复总耗时 |
| `exchange_snapshot_saved_sequence` | 本次进程成功保存或幂等确认的快照位置；首次成功前为 -1 |
| `exchange_snapshot_last_success_timestamp` | 快照保存成功时间（Unix 秒），不是最近交易时间 |
| `exchange_snapshot_unsaved_sequence_distance` | 已执行位置减已保存位置；序号差不是事件条数，首次快照成功前没有业务意义 |
| `exchange_snapshot_save_total{result}` | 捕获、编码或持久化失败都记失败；失败不推进保存位置 |
| `exchange_snapshot_save_duration_seconds_*` | 包括捕获、编码和数据库返回在内的耗时 |
| `exchange_snapshot_saving` | 是否正在保存；短暂状态可能在两次抓取之间完成 |
| `exchange_output_pending{queue}` | orders、matches、ticks、api_results、notifications 队列元素数；数据库元素可能是一批记录，不含已取出处理中任务 |
| `exchange_output_worker_alive{worker}` | tick、notify、api_result、orderbook、db 线程是否存活 |
| `exchange_output_failures_total{worker}` | 输出线程未处理异常次数，不包含 Kafka 异步发送完成后的错误 |
| `exchange_output_workers_expected` | 是否处于预期输出线程工作阶段 |
| `exchange_tick_send_calls_returned_total` | Tick 批量发送调用正常返回次数，不是消息数、broker 确认数或消费成功数 |

队列计数不遍历队列，抓取不取得引擎锁、不查询资产或事件表。并发瞬间可能存在短暂的计数与可出队元素差异；静止后准确。订单簿是最新引用，不是队列，当前未提供订单簿投递位置差指标。

Counter 和 Timer 在 JVM 重启后归零，历史由 Prometheus 自己保存。空闲阶段快照跳过不算成功或失败，因此“快照时间较旧”不能单独当故障。平均耗时在没有新样本时可能显示无数据。大序号展示使用浮点值，仅供观测，不能用于交易记账或恢复判定。

健康检查不会自动修复退出线程，也不修改 `/internal/status` 原有业务门控。输出线程退出时管理 readiness 会失败，但原业务状态仍可能 READY；后续可单独完善输出故障门控及可靠投递。Kafka send 返回不等于 broker 确认，本阶段不宣称端到端投递成功。外部依赖中断但线程暂未报错时，也可能仍报告就绪。

## 测试与联调验收

```powershell
mvn -f build/pom.xml test
```

自动化覆盖：生命周期和健康状态、保存失败保留旧位置、实时/重放区分、重复事件、空闲跳过、失败重试、并发队列，以及隔离 Spring HTTP 管理端口的响应和敏感接口关闭。不连接真实 MySQL、Redis、Kafka；不能替代下面的联调。

联调指让真实组件一起运行并检查它们之间的数据链路。本阶段没有启动监控容器替你完成联调，启动后按以下步骤验收：

1. 引擎启动后，Targets=UP、ready=1，五个输出线程=1；快照位置与恢复位置一致。
2. 在两个独立浏览器中完成挂单、成交、撤单，确认资产功能正常；live/applied 增长，执行位置增长；输出任务清空，线程保持存活。
3. 有新事件后等待超过快照检查间隔，快照位置追上执行位置，成功计数增长；无新事件时成功计数不增长。
4. 保留全部业务数据，正常重启引擎，比较资产和活动订单；恢复位置正确，旧事件不会重复通知，replay 指标只包括实际重放部分。不要为了“产生重放指标”删除快照或数据。
5. 正常停止引擎，Targets 最迟下一次抓取变 DOWN。此时业务指标可能在查询窗口暂时保留旧样本，不能只看最后一次 ready=1。

故障注入仅在隔离测试环境进行，不要在当前用户资产环境中故意断库、破坏快照或杀输出线程。本阶段未加入 Alertmanager/通知规则，因此看到异常还不会主动发消息。

镜像使用固定版本；维护时参考 [Prometheus 官方安装文档](https://prometheus.io/docs/prometheus/latest/installation/) 和 [Grafana 官方 Docker 文档](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/)。JSON 仪表盘不支持注释，其职责和限制在本说明及 dashboard.description 中记录。
