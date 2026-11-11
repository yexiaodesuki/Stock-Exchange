<!-- 第三阶段实际交付：持久输出、行情、鉴权 API 与分证券/用户推送；不宣称股票 UI 或生产级恰好一次。 -->
# 第三阶段：行情、查询与推送

> 历史实现记录：本页的 dev 启动脚本和旧 Compose 已移除。当前启动及验证以 [running.md](running.md) 为准，勿执行本页旧环境命令。

## 当前能力与入口

新增证券 quotation、trading-api、push 独立启动包，保留所有旧 BTC 入口。
撮合结果可恢复地进入订单历史、成交明细、清算记录、Tick、UTC 四周期 K线和证券 Redis 缓存。
API 提供本人现金/持仓/订单、历史与成交查询、幂等下单撤单及结果查询；Push 支持证券订阅和本人私人提示。
股票页面、登录页面、演示账号和自动配给仍属于第四阶段。没有公开发钱、发股票或任意指定用户获取令牌的接口。

## 文件索引

| 文件（相对仓库） | 本次实际改动 |
| --- | --- |
| build/sql/securities/V2__outputs_and_projections.sql | 新增 5 表，ticks 增加 tradeIndex 和事件索引，不改 V1 |
| common/.../securities/output/ExecutionEnvelope.java | 完整订单变化、逐笔成交剩余量、聚合盘口等不可变值 |
| common/.../securities/output/OutputStore.java | 输出、历史订单/明细/清算/配给记录、Outbox 与进度同事务 |
| common/.../securities/output/MarketStore.java | Tick、四周期 K线与行情进度同事务；时间/数量白名单查询 |
| common/.../securities/output/MarketCache.java | 每证券文档原子替换、精确序号 CAS、一次性 Redis 票据 |
| common/.../securities/output/OutboxPublisher.java | 确认后发送 Kafka/Redis，失败重试，静默修复当前盘口缓存 |
| common/.../securities/output/TopicSignal.java | 新 Tick Topic 唤醒行情持久追赶，Kafka 不可用时保留轮询 |
| common/.../securities/output/SecuritiesTokens.java | 证券用途 HMAC、过期/用户校验、常量时间签名比较 |
| common/.../securities/output/WireJson.java | 对外数值转精确字符串，避免 JavaScript/中间 JSON 丢精度 |
| common/.../securities/persistence/ProcessLease.java | 增加行情实例角色，防止多个投影写者同时更新同一库 |
| trading-engine/.../core/ExecutionResult.java | 增加完整变化订单；逐笔成交保留清算后双方剩余量 |
| trading-engine/.../core/SecuritiesEngine.java | prepare/install 分离，复制关闭订单，公开价量聚合盘口 |
| trading-engine/.../TradingEngineService.java | 先持久输出后装内存，按输出位置限制恢复快照，自动补齐旧交易 |
| trading-engine/.../config/EngineConfiguration.java | 新输出发送器、缓存 Bean 和独立可关闭发送配置 |
| trading-engine/.../web/api/InternalTradingEngineApiController.java | 公开给本机代理的聚合盘口、本人活动订单和证券版本 |
| quotation/.../securities/quotation/* | 新持久投影、缓存重建、低延迟 Kafka 提示和真实就绪 |
| trading-api/.../securities/api/* | 新签名鉴权、本人查询、缓存回源、202 结果查询与幂等预留 |
| push/.../securities/push/* | 新 Vert.x 票据握手、按证券/用户路由、限流、心跳、背压和重订阅 |
| 三模块 pom.xml、securities-*.yml | 额外 -securities 包、独立连接/端口，旧入口不变 |
| dev.ps1/dev.sh、start-core.ps1 | 增加三服务启动和本地认证密钥自动补充，保留旧密码 |
| check-db.sh | 验证 V1/V2、24 张业务表所需结构与 Tick ordinal |
| test-phase3.ps1、test-phase2.ps1 | 复用临时库生命周期，自动执行所有版本迁移和全模块回归 |
| 新单元/数据库/Redis/进程/脚本测试 | 输出原子性、隔离、降级、恢复与 PowerShell 编码回归 |

所有新核心应用继续在 `com.itranswarp.securities` 命名空间中显式装配，避免扫描旧 BTC 组件。

## 一、执行结果如何可靠保存

旧 runtime 丢弃 `engine.execute` 返回值。本次使用：

```java
var prepared=engine.prepare(event);
outputs.save(event,prepared.output(),
        mode==SecuritiesEngine.Mode.REPLAY || event.sequenceId()<=recoveryUpper);
engine.install(prepared);
```

prepare 在候选状态完成冻结、撮合、清算和全量校验，不修改当前资金。
OutputStore 的一笔 MySQL 事务保存：engine_results、orders、match_details、clearings、transfer_logs、event_outbox 和 engine_output_checkpoint。
数据库写入失败，不安装候选，运行时 FAILED，不查询半状态、不保存领先快照。
数据库已经提交而安装前突然退出，重启按同一事件重新准备，结果 SHA-256 必须一致；不重复历史、不重复资金配给。
正常业务拒绝也持久化 accepted=false/reason 并推进位置，不能把余额不足当成历史损坏。

ExecutionResult 现在记录关闭前订单的完整字段，完全成交和撤单也有历史。
Trade 的 makerRemaining/takerRemaining 是本笔清算后的数量，而不是整张 Taker 全部成交结束后的统一数量。
因此一张单吃多个 Maker 时清算明细仍能正确表示每步状态。
公开盘口只有价量和版本，不含个人订单标识。公开 Tick 不含 buyerId/sellerId。

### 已有第二阶段数据的升级

```java
engine=snapshots.restore(Math.min(target,outputs.position()));
```

若旧核心快照已经到 S，但新输出位置为零，就不能从 S 跳过此前成交。
系统自动选安全快照或从零重放，补齐输出/历史；历史执行不创建私人及盘口实时 Outbox，只为行情留下可追赶来源。
补齐过程不发放新资产，不修改 events，不把 BTC 映射为股票。
只有完整恢复和启动快照成功后 READY。首次补齐可能较慢，期间返回 503，不返回假空余额。
恢复期间新提交的有限尾部也按 REPLAY 补齐，不向刚登录用户重新轰炸旧通知。

## 二、Outbox 与重试边界

event_outbox 使用 `sequence:destination:routing` 唯一事件标识。后台仅发送 sequenceId<=内存已应用位置的记录。
Tick 发送等待 Kafka 确认，失败保留 sentAt=NULL。发送后但标记前崩溃可能重复，下游用持久位置幂等。
Quotation 同时消费 Tick 唤醒和轮询 engine_results；消息最后一条丢失也能追赶。
资金权威仍是有序事件＋一致性快照，Outbox 不是另一份资金账户。

Redis 私人和盘口消息是实时提示，发送器重启前的旧实时消息会静默丢弃并标记完成，不补发旧私人通知。
当前盘口会定期从 READY 引擎静默重建，即使 Redis 丢失数据且没有新订单，也能恢复缓存。
Redis Pub/Sub 不保存离线历史；本阶段不承诺通知恰好一次、离线必达或零丢失。
请求最终结果持久存在 MySQL，不能仅用 WebSocket 通知判定下单成败。
尚未引入 Outbox/快照清理和保留策略，长期运行要管理磁盘容量。

## 三、行情的持久化与缓存

MarketStore 每个完整结果：锁行情进度→检查前序→插入 Tick→更新 sec/min/hour/day K线→推进进度→提交。
重复结果直接返回，不重复累加数量；某张 K线表写失败时 Tick 和进度一同回滚。
一事件多成交使用 `(sequenceId,tradeIndex)` 确定稳定先后，不能按整事件去重丢掉后续成交。
K线使用事件 createdAt，按 1000/60000/3600000/86400000 毫秒切 UTC 时间桶。
Open 为首笔，Close 为末笔，High/Low 为极值，Quantity 为整数股数总和。当前未收盘 K线也持续写 MySQL。
没有成交的时段不造 K线，没有外部实时股票价格或真实交易时段规则。

实际 Redis 键采用更小的原子文档方案，而非多个 Key 加代次指针：

```text
securities.v1:1001:book
securities.v1:1001:quotation
securities.v1:1002:book
securities.v1:1002:quotation
```

每个键为 Hash，包含 version 和 data，单次 Lua HSET 原子替换整份文档，所以读者不会看见半份重建。
quotation 含最近 100 笔和每周期最近 200 根 K线。更长历史或超出窗口的查询回 MySQL。
Lua 序号按字符串长度/字典序比较，禁止用 double 比较大于 2^53 的序号。
API 盘口检查的是该证券 marketVersions，不把另一股票成交的全局进度误认为本证券必须变化。
缓存失效：盘口回引擎，Tick/K线回 MySQL，资金始终回引擎。
行情未追上持久输出时明确 503，不用空数组冒充没有成交。
MySQL 回源用同一个事务视图读取进度和数据，缓存响应也使用实际缓存版本，不能给旧数据贴新进度。

## 四、API 契约与安全

| 路径 | 身份/含义 |
| --- | --- |
| GET /api/v1/securities | 公开目录 |
| GET /api/v1/securities/{id}/orderbook | 公开聚合盘口 |
| GET /api/v1/securities/{id}/ticks?limit=100 | 公开最近成交 |
| GET /api/v1/securities/{id}/bars/{sec|min|hour|day} | 公开 K线；from/to 为毫秒，limit 1～1000 |
| GET /api/v1/account | Bearer 认证得到的本人 USD 和五持仓 |
| GET /api/v1/orders?securityId=1001 | 本人活动订单，来自引擎 |
| GET /api/v1/history/orders?securityId=1001 | 本人最终订单，包括部分撤销 |
| GET /api/v1/orders/{id}/matches?securityId=1001 | 检查归属后返回本人成交明细，不含对手用户身份 |
| POST /api/v1/orders | Bearer＋Idempotency-Key，证券/方向/价格/整股数量 |
| POST /api/v1/orders/{id}/cancel?securityId=1001 | Bearer＋Idempotency-Key，核心再次校验归属 |
| GET /api/v1/requests/{id} | 本人持久结果，未完整执行返回 202 |
| POST /api/v1/ws-ticket | 已认证身份换 30 秒一次性连接票据 |

API 不从 JSON/URL 的 userId 选择用户。令牌限定 securities 用途、最多一小时，使用独立 SECURITIES_AUTH_KEY 和 HMAC-SHA256。
密钥至少 32 字符、无默认值，由 infra 首次补充到被忽略的 .env.securities；已有数据库密码不变。
没有公开签发任意用户令牌的方法。第四阶段登录服务才调用受信内部签发方法。

api_requests 先预留用户绑定的业务幂等键和命令哈希，并发不同内容返回 409，不能把冲突请求送进定序器导致毒消息。
Kafka 确认后返回 202 SUBMITTED，不代表撮合成功。等待请求执行结果得到 accepted/reason。
网络不确定返回 503 SUBMISSION_UNCERTAIN 和 requestId，同一键重试，不换键重复下单。
他人的请求/订单明细返回 404，未认证为 401，非法参数为 400，依赖或状态不可用为 503。
金额、股数、长整型序号等对外转精确字符串，避免浏览器 double/中间 JSON 转换丢失精度。
请求结果查询也检查持久校验和，损坏结果不能冒充执行成功。

## 五、WebSocket 协议

连接方式：`ws://127.0.0.1:8106/ws?ticket=<一次性票据>`。
API 和 Push 通过 Redis 原子 GETDEL 消费票据，票据不能重复握手，也不能延长原身份有效期。
Origin 默认只允许 http://localhost:8100 和 http://127.0.0.1:8100，可通过 SECURITIES_ALLOWED_ORIGINS 显式配置；不开放通配 Origin。
握手异步访问 Redis 前暂停 HTTP 请求流，验证完成后升级再恢复，避免 Vert.x 的 Request has already been read。

```json
{"action":"subscribe","securityIds":[1001,1002]}
```

```json
{"action":"unsubscribe","securityIds":[1001]}
```

市场消息仅发给所订阅证券的连接。私人 account.changed 只根据握手用户路由，切到 GREEN 页面仍可收到本人 STAR 提示。
公开消息剥离交易用户身份，私人路由不是客户端可以提交的订阅字段。
欢迎和 subscription.ack 只表示建立连接/订阅成功，不表示初始数据已同步。
第四阶段客户端应先订阅、暂存实时帧，再拉 HTTP 快照，以 eventId/sequence 合并并忽略旧响应；断线后重新拉账户/订单/盘口/近期行情。

限制：消息 4KB 业务输入、订阅标识最多 5 个、每秒最多 20 条订阅操作、写队列 256KiB 满则断开慢客户端。
20 秒心跳，60 秒无响应或身份过期关闭。所有会话到期后不再发送私人或公共数据。
Redis 断开降低健康状态并提示 resync.required；专属工作线程重新 PSUBSCRIBE 确认后恢复健康和同步提示。
没有测试整机断网/生产 Redis 高可用切换，不将重连代码存在说成这些故障已全部验收。

## 启动与迁移

本次已在证券开发库自动应用非破坏性 V2：新增 5 表＋Tick ordinal/index，V1 不变。
首次克隆会依次自动执行 V1/V2，不需要手工逐个 SQL。
已有证券 Java 进程需自行停止后用新包重启；脚本不会擅自停止你的 IDEA 或未知 JVM。

```powershell
.\dev.ps1 infra
mvn -f build/pom.xml package

# 五个终端，各启动一个新证券包。
.\dev.ps1 sequencer
.\dev.ps1 engine
.\dev.ps1 quotation
.\dev.ps1 api
.\dev.ps1 push
```

默认端口：API 8101、引擎 8102、定序 8103、行情 8105、Push 8106；前四者管理端口 18101/18102/18103/18105。
Push 的 `/actuator/health` 在 Vert.x 8106 端口，不额外伪造一个 Spring HTTP 服务。
所有监听仍限本机；本阶段没有 Nginx、公网 TLS、登录页或生产网关防护，禁止直接当公网服务部署。
旧 UI 8000 尚未接入，不要把它直接指向此 API 或期待出现股票选择器。
空开发库仍没有演示用户/资金/成交；不会为了显示 K线偷偷插入伪造行情。
Linux/macOS 对应 `bash dev.sh <服务名>`，本次完整联调在 Windows，Bash 仅做语法检查。

PowerShell 脚本统一 UTF-8 BOM，并用 5.1 和 7 的解析器做无副作用回归，修复此前中文脚本误按 ANSI 读取的问题。
脚本及日志不提交密码，AGENTS.md、.env.securities、构建产物仍被忽略。

## 验收与边界

```powershell
.\build\securities\test-phase3.ps1
```

运行器复用临时库生命周期：随机测试库→自动迁移所有版本→打包→全模块 verify→精确清理。
测试 Redis/Topic/消费组均是独立随机前缀，不使用 FLUSHDB、全局 prune、重置开发消费位置或清空容器。

新增用例包括：

- SecuritiesWireTest：签名/用途/有效期和精确数值传输。
- SecuritiesOutputTest：prepare 未安装资金、输出提交后重试幂等、输出失败停止、五市场投影、多成交 ordinal、旧快照补齐、撤单历史、K线中途失败回滚。
- SecuritiesCacheTest：真实 Redis 大序号比较、证券隔离、一次性票据、原身份过期。
- SecuritiesScriptTest：PowerShell 5.1 和 7 均解析全部证券脚本且确认 BOM。
- SecuritiesPhase3ProcessTest：真实五个服务，经鉴权 API 完成五证券交易，查询 Tick/K线/活动单，检测请求/用户越权和幂等冲突，实际 WebSocket 证券/用户隔离，重复输出不重复成交量，行情重启重建，Redis 不可达时资金/行情回源与票据 503。
- 第二阶段全部核心、数据库及强制终止恢复用例仍运行。

当前没有浏览器股票页面端到端测试（页面未实现），没有生产吞吐测试、离线通知必达、多实例资金高可用或主机断电保证。
单证券行情文档限长，后台目前在小规模五证券下轮询重建；后续可优化空闲查询和批次/异步缓存，不将此实现宣传为高频交易架构。
完整统计以本次交付报告及 surefire-reports 为准，不把条件跳过的旧环境联调计为通过。

2026-10-27 最终实际验收：全模块 verify 成功，179 项报告记录中 177 通过、0 失败、0 错误、2 个条件跳过占位项。
跳过组为旧环境 LiveDependencyMonitoringTest 和第一阶段针对开发库的 SecuritiesDatabaseTest；第三阶段新增 15 项均已运行通过。
真实五服务联调还验证了 API 部分成交撤单、历史状态、本人明细权限、取消订阅和未订阅 GREEN 时仍收到本人 GREEN 提示。
开发库 V1/V2 已应用且校验通过；临时库、测试组/Topic、Redis 测试前缀全部清理，开发库没有测试资金/订单写入。
完整进程验收在 PowerShell 7 执行，5.1 和 7 均有脚本编码/语法解析回归，不把语法检查说成 5.1 完整进程联调。
旧 Grafana/Prometheus 目标与仪表盘未重写；新服务提供独立健康端点，面板接入和通知告警需后续安排。
