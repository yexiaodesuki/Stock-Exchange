<!-- 第二阶段监控说明：应用视角、独立端口、缓存探测及真实验证边界；不承诺可靠投递或资金对账。 -->
# 系统与依赖监控（第二阶段）

## 目标和边界

七个应用均提供 JVM、进程和就绪观测。Spring MVC 应用使用 Actuator；Push 保留 Vert.x 和 WebApplicationType.NONE，通过独立 Vert.x 监听器暴露兼容的指标与健康路径。

本阶段不改资产、订单、撮合、清算、快照格式或 Kafka Topic/消费组/offset 策略。不提供告警通知、链路追踪、日志平台、可靠输出补偿、资金对账。依赖监控是**应用视角**，不是 MySQL 全局慢查询、Redis 服务端内存或 Kafka broker 磁盘监控。

## 端口和启动

| 应用 | 业务端口 | 管理端口 | 后台依赖探测 |
| --- | --- | --- | --- |
| ui | 8000 | 18000 | MySQL、Kafka |
| trading-api | 8001 | 18001 | MySQL、Redis、Kafka |
| trading-engine | 8002 | 18002 | MySQL、Redis、Kafka |
| trading-sequencer | 8003 | 18003 | MySQL、Kafka |
| quotation | 8005 | 18005 | MySQL、Redis、Kafka |
| push | 8006 | 18006 | 真实 Redis 连接和通知订阅状态 |
| config-server | 8888 | 18888 | 本地配置与 Boot 生命周期，不请求自身配置 |

UI 的公共 MessagingFactory 目前启动时也会访问 Kafka；因此 UI 展示 Kafka 探测，但不表示每次页面请求都会使用 Kafka。Push 不使用公共探测器，监控的就是自己实际使用的 Vert.x Redis 订阅。

保留原有容器和全部业务数据，IDEA 重新加载 Maven，先重启 ConfigApplication，再按原有顺序启动或重启各业务应用。仅修改配置文件不会让已经运行的 Java 应用自动加载新的埋点和配置。

管理端口分别提供：

```text
/actuator/health/liveness
/actuator/health/readiness
/actuator/health
/actuator/prometheus
```

例如引擎指标地址是 `http://localhost:18002/actuator/prometheus`，API 是 `http://localhost:18001/actuator/prometheus`。

管理接口仍未配置认证，为 Docker Desktop 抓取默认监听 0.0.0.0；必须限制在受信任本地网络，不能映射公网。Prometheus/Grafana 界面仍仅发布到 127.0.0.1。不开放 env、heapdump、shutdown。

修改管理端口时同步修改 `build/monitoring/prometheus.yml`。用 `docker exec stock-exchange-monitoring-prometheus-1 promtool check config /etc/prometheus/prometheus.yml` 校验后，发送 `docker kill --signal=HUP stock-exchange-monitoring-prometheus-1` 热加载即可，不需要重建业务容器。

## 怎么看面板

Grafana 的 Stock Exchange 文件夹中保留“交易引擎”，新增“系统总览”“依赖与消息链路”。Prometheus Targets 应有七个目标。没启动某个应用时该目标 DOWN 是预期状态，不代表配置自动启动了应用。

查看顺序：抓取 → 本地就绪 → 依赖可用/有效性 → API 等待 → 定序进度 → 引擎 → 行情 → Push。只看 `up=1` 不能证明业务链路成功。

系统总览的 HTTP 指标来自 Spring MVC，不包括 Vert.x Push；Push 使用专门的连接与写入指标。HTTP 5xx 与业务拒绝不同：余额不足等可能返回 4xx，不应全部当服务器故障。

原始 Counter 在 JVM 重启后归零；Prometheus 保存历史。部分 Counter/Timer 直到首次出现对应操作才注册，因此从未发生的异常、空闲阶段平均耗时可能 No data，不自动等于故障。rate/increase 基于采样窗口，不是精确订单账本；短暂启动失败和快速重放可能来不及抓取。

## 本地健康与依赖缓存

`ApplicationReadiness` 在 ApplicationReadyEvent 后才就绪，停止时撤销。API 还要求真实结果订阅，定序还要求工作线程和 SEQUENCE 容器，行情还要求 TICK 容器。容器 isRunning 不是分区已分配或每条消息已处理的证明。引擎继续使用第一阶段的恢复和五个输出线程检查。

外部依赖不加入 liveness/readiness 分组，也不改变原 `/internal/status`、拒单、重试或自动重启规则。聚合 `/actuator/health` 包含依赖状态，所以可能聚合 health=DOWN、本地 readiness=UP；两者语义不同。

依赖默认每 15 秒后台检查一次，结果 45 秒后过期；检查间隔可通过 `DEPENDENCY_PROBE_INTERVAL_MS` 配置，至少 1000ms。`DEPENDENCY_MONITORING_ENABLED=false` 可关闭公共依赖探测，但不关闭已有业务连接。

- MySQL：使用应用连接池的 `Connection.isValid(2)`，不执行业务表扫描或写入；获取连接受现有池超时限制。Hikari 指标是本应用池，不是 MySQL 全局统计。
- Redis：公共探测使用独立连接做 PING，连接与命令分别设置 2 秒超时，不改业务命令超时；业务池计数和 executeSync 耗时另行统计。
- Kafka：独立 AdminClient 在 2 秒预算内读取集群元数据，不创建 Topic、不加入消费组、不重置 offset。
- 每个依赖仅一个固定延迟任务，不因卡住不断堆积线程。若驱动未按时返回，旧缓存也会过期，不能继续假报正常。抓取与健康接口不等待这些探测。

`exchange_dependency_up=0` 可能是失败、未知或过期；同时看 `exchange_dependency_fresh` 和最近检查时间，不能把未知冒充明确连接失败。

## 主要指标与单位

| 指标 | 含义 |
| --- | --- |
| exchange_component_ready{component} | 本地组件当前是否就绪；application 是应用就绪 |
| exchange_dependency_up{dependency} | 最近有效探测是否成功 |
| exchange_dependency_fresh{dependency} | 最近探测结论是否仍有效 |
| exchange_dependency_checked_timestamp | 最近完成检查的 Unix 秒 |
| exchange_dependency_checks_total{dependency,result} | 探测次数，success/failure 分开 |
| exchange_dependency_duration_seconds_* | 探测耗时 |
| exchange_redis_pool_active / idle / waiting | 自定义 Redis 池连接和等待数 |
| hikaricp_connections_active / pending | 应用 MySQL 池活跃连接与等待线程 |
| exchange_pipeline_operations_total{stage,result} | 固定阶段、结果计数；单位见下面说明 |
| exchange_pipeline_duration_seconds_*{stage} | 阶段耗时；不等于浏览器端到端耗时 |
| exchange_pipeline_completed_sequence{stage} | 定序事务返回/行情完整调用成功后的序号 |
| exchange_api_requests_pending | 当前仍在等待观测终止的下单、撤单请求数 |
| exchange_push_connections | 实际注册 WebSocket 数，包括匿名连接 |
| exchange_push_redis_connected / subscribed | Push 实际连接和订阅确认状态 |
| exchange_push_operations_total{stage,result} | 连接、通知接收和 socket 写入结果 |
| kafka_consumer_fetch_manager_records_lag | Kafka 原生客户端已分配分区 lag，不是完整消费组已提交 offset 积压 |

`exchange_pipeline_operations_total` 各 stage 单位不同，不要把所有 stage 相加当成交数：

- `api_create/api_cancel`：每个等待请求终止一次，success、rejected、timeout、send_failure、expired 或 completed_without_result。内部 refId 仅用于移除一次，不作为标签；迟到结果不重新计数。
- `engine_gate`：代理统一统计每次显式引擎就绪检查的 success/failure；控制器与发送入口可能分别检查，因此不是请求数。在等待项创建前被拒绝的请求可能没有 api_create 终止样本。
- `sequence`：success 按本批实际定序事件数；failure 按失败调用次数。成功只在事务代理提交正常返回后统计，序号对象在方法内部增长不等于提交成功。
- `quotation_messages/quotation_ticks`：分别按 TickMessage 数和其中成交记录数。quotation success 是完整方法成功返回，不是 Kafka 刚收到消息。
- `quotation`：success/failure/duplicate/redis_rejected。Redis 拒绝或后续异常不推进完整成功指标。本阶段不修复原投影的去重、重试和恢复缺口。
- `kafka_send_SEQUENCE/TRADE/TICK/...`：returned 为调用正常返回，acknowledged 为 Future 正常完成，async_failure 为异步失败，sync_failure 为同步异常；仍不等待 broker，不改变发送接口。broker 确认不证明下游消费完成。
- `kafka_consume_*`：success 按正常处理的批消息条数，failure 按失败批调用次数；不等于 offset 已提交或恰好一次。
- `redis_command`：每次 executeSync 正常或异常一次，包括借连接等待。

Push 保持用户路由与消息内容，向选中的 socket 直接异步写入，以观察真实写入 Future，而非只统计事件总线提交；completed 不表示浏览器已经处理。启动欢迎消息不计入业务通知写入数。Push 的 Redis 配置仍沿用原 spring.redis.standalone.* 字段，不在本次悄悄改成 spring.redis.*。

## 测试与验收

普通回归不连接现有业务容器：

```powershell
mvn -f build/pom.xml test
```

新增覆盖：未知/失败/过期探测、应用本地组件、Kafka 受控 Future 成功与失败、API 终止幂等、真实事务代理提交失败、行情异常和 Redis 拒绝、随机端口 HTTP 与 WebSocket（匿名广播/私人路由）、真实 YAML 合并后的端口隔离和敏感接口关闭。HTTP 隔离测试不扫描业务 Bean，不启动真实业务消费者。

真实只读容器探测默认跳过，可显式设置 `EXCHANGE_MONITORING_LIVE_TESTS=true`、`LIVE_DB_PASSWORD`（及必要的 LIVE_JDBC_URL、LIVE_DB_USERNAME），执行：

```powershell
mvn -f build/pom.xml -pl :common -am '-Dtest=LiveDependencyMonitoringTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

密码只通过当前终端环境传入，不写进 Git；运行后清除环境变量。此测试只校验 JDBC、PING、Kafka 元数据，不提交订单或操作业务数据。Redis 和 Kafka 目标为当前本地默认端口；非默认环境需先调整测试参数实现，不应假称已验证。

`build/monitoring/verify-app-startup.ps1` 是另一个**有应用副作用**的人工验收脚本。它会逐个启动真实应用，引擎可能按正常流程保存快照、消费者可能处理已有消息；不是只读探测。默认拒绝执行，必须显式传入 `-AllowApplicationStartup`。脚本拒绝占用已运行应用的端口，只关闭自己创建的进程，日志在独立临时目录。需要先构建全部包，并为配置中心执行 `mvn -f config/pom.xml -DskipTests package spring-boot:repackage`。

本次真实应用启动操作被自动安全检查阻止，未绕过；只读容器探测、隔离 HTTP/WebSocket、配置和面板查询验证与真实完整应用启动必须分开报告。

最终人工端到端验收仍包括两用户挂单、成交、撤单，以及保留数据重启后的资产和活动订单比较；故障注入只在隔离环境执行，禁止为监控验收清空库、清缓存或重置消费位置。

## 本次验证记录（2026-10-22）

- 全项目 `mvn -f build/pom.xml verify`，本次临时开启只读容器测试：114 项，失败 0、错误 0、跳过 0；凭据未输出或写入仓库。
- 原有资产、撮合、重放和快照回归保留通过。普通 `mvn test` 默认跳过真实容器测试一项，避免在未知环境擅自连接服务。
- 实际 MySQL JDBC 校验、Redis PING、Kafka 元数据探测通过。
- Spring MVC 管理隔离、真实配置合并、Push NONE 健康分组，以及随机端口匿名广播/私人路由测试通过。
- 实际 Prometheus `promtool check config` 通过；43 条面板查询执行成功。这证明查询合法，不证明尚未启动应用已有业务数据。
- 只对 Prometheus 发送 HUP 热加载配置，七个抓取目标已注册；没有停止或重建 MySQL、Redis、Kafka、Grafana。
- Grafana API 确认自动导入 exchange-engine、exchange-system、exchange-dependencies 三张仪表盘，原数据源仍为 exchange-prometheus。
- 验证时七个完整业务/配置应用未启动，实际 Targets 为 DOWN；未把隔离测试误写为真实完整应用启动通过。
- 真实应用启动脚本被安全检查阻止，原因是可能保存恢复快照及处理已有 Kafka 消息。未绕过限制、未执行测试交易；这部分需要再次明确允许后验收。
- 验收脚本 PowerShell 语法检查通过；默认需显式允许启动，未执行。现有本地文档修改保留，没有提交或推送 Git。

## 关键实现与代码边界

### 依赖状态不是上一次成功的永久缓存

`common/.../monitoring/DependencyStatus.java` 中：

```java
public boolean fresh() { return known && clock.getAsLong() - checked <= validityNanos; }
public boolean up() { return fresh() && success; }
```

`known` 防止首次探测之前误报正常；单调时钟避免系统时间调整影响过期判断。`DependencyMonitoringService` 的后台任务完成后调用 record，管理接口仅调用 health 读取这些状态，因此监控抓取不等待 JDBC 或 Redis。

### API 超时与迟到结果不能重复计算

`trading-api/.../service/ApiRequestMonitoring.java` 中：

```java
Sample sample = pending.remove(refId);
if (sample != null) {
    monitoring.count("api_" + sample.operation(), result);
    monitoring.duration("api_" + sample.operation(), sample.started());
}
```

ConcurrentHashMap 的 remove 由第一次终止观察移除；后续超时、迟到结果或清理取不到样本，不再计数。TradingApiController 在 put 等待项后、发送命令前记录起点，并在发送失败、超时、结果和完成路径调用 finished；JSON 响应、HTTP 状态和业务超时保持原值。TradingEngineApiProxyService 统一统计所有显式 requireReady，避免只在 SendEventService 埋点漏掉控制器提前拒绝。

### 定序成功必须在提交返回后

`trading-sequencer/.../SequenceService.java` 中，以下语句顺序不可颠倒：

```java
List<AbstractEvent> result = sequenceHandler.sequenceMessages(messageTypes, sequence, messages);
monitoring.count("sequence", "success", result.size());
monitoring.progress("sequence", sequence.get());
return result;
```

调用对象仍为 Spring 事务代理。测试用真实 TransactionInterceptor 与隔离事务管理器，在业务方法返回后的 doCommit 抛异常，断言成功位置仍停留在旧值。监控不改变事件持久化与 Kafka 发送之间原本存在的可靠性边界。

### 行情完整返回与提前设置的业务序号分离

`quotation/.../QuotationService.java` 保留原方法体在 applyMessage，只把处理结果返回给外层：

```java
if (applyMessage(message)) {
    monitoring.count("quotation", "success");
    monitoring.progress("quotation", message.sequenceId);
}
```

旧消息或 Redis 拒绝返回 false；异常继续抛出并记录 failure。独立成功指标不会因原业务字段先赋值而推进。真实 Redis 拒绝分支和中途失败均有回归测试，但这不是对旧投影恢复问题的修复。

### Push 就绪必须同时满足真实业务条件

`push/.../PushMonitoringService.java` 中：

```java
public boolean ready() { return !stopping && httpListening && connected && subscribed; }
public void connected(boolean value) { connected = value; if (!value) subscribed = false; }
```

监听成功、连接成功、SUBSCRIBE 确认由 PushService/PushVerticle 的真实回调更新。连接结束立即撤销订阅状态。该 Bean 同时是 applicationHealthIndicator，避免 NONE 模式下全局健康分组引用不存在的组件。PushManagementServer 只承载管理路径；原 8006 WebSocket 地址保留，旧固定 UP 路由改为实际就绪结果。

### 消息结果必须区分多个阶段

`common/.../messaging/MessagingFactory.java` 不再丢弃发送 Future 的观察机会，但仍不阻塞等待：

```java
kafkaTemplate.send(name, messageTypes.serialize(message)).whenComplete((result, error) -> {
    monitoring.count("kafka_send_" + name, error == null ? "acknowledged" : "async_failure");
    monitoring.duration("kafka_send_" + name, started);
});
monitoring.count("kafka_send_" + name, "returned");
```

同步异常另在原调用线程记录 sync_failure 和耗时后原样抛出；回调不重发消息。生产者/消费者工厂的 Micrometer listener 管理原生客户端指标，消费容器的运行状态通过只读 supplier 绑定。returned、acknowledged、消费回调正常返回、业务完整执行、offset 提交并不是同一件事。
