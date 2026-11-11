<!-- 第二阶段实际实现：现金持仓、分证券撮合清算、定序与整体恢复；不等同于行情/UI 已改造。 -->
# 第二阶段：核心交易与恢复

> 历史实现记录：本页的 dev 启动脚本和旧 Compose 已移除。当前启动及验证以 [running.md](running.md) 为准，勿执行本页旧环境命令。

## 完成范围与入口

实现了五证券共享 USD、分证券持仓、限价整股委托、价格时间优先撮合、Maker 价清算、价差退回、部分成交和撤单。
核心状态由 MySQL 有序事件和格式 2 一致性快照恢复；Kafka 提示丢失不阻止数据库尾部追赶。
新增独立证券启动入口，旧 BTC 代码和默认包继续保留。旧 UI、旧 API、旧行情和 Push **尚未接入新核心**。
内部配给只通过受信证券 Kafka 通道提交，不新增公开充值/下单 HTTP 接口。

## 文件与职责

以下路径均相对于仓库根目录。

| 文件或目录 | 本次具体职责 |
| --- | --- |
| common/.../securities/SecuritiesProtocol.java | 新增 Submission，验证协议、目录、普通用户和幂等标识；不继承旧消息 |
| common/.../securities/SecuritiesChannels.java | 正式与测试 Topic/消费组名称隔离，前缀严格白名单 |
| common/.../securities/persistence/EventJournal.java | 行锁串行定序，事件/幂等键同事务提交，内容冲突拒绝，分页解码核对列 |
| common/.../securities/persistence/ProcessLease.java | 单数据库/角色的 MySQL 会话锁，拒绝第二引擎或第二定序器 |
| trading-engine/.../securities/core/EngineState.java | 共享现金、分证券持仓、订单、五盘口及深复制候选状态 |
| trading-engine/.../securities/core/SecuritiesEngine.java | 完整事件执行、拒绝、撮合清算、撤单、守恒校验、快照捕获和恢复 |
| trading-engine/.../securities/core/ExecutionResult.java | 不可变实时结果和成交值；历史重放不返回可发送结果 |
| trading-engine/.../securities/core/SecuritiesSnapshot.java | 格式 2 值对象，包含目录指纹、全局位置、现金、持仓、订单、五市场 |
| trading-engine/.../snapshot/SnapshotRepository.java | 原文 SHA-256 与列/JSON 校验、候选回退、同位置一致、追加保存 |
| trading-engine/.../securities/TradingEngineService.java | 启动恢复、就绪控制、数据库尾部追赶、周期快照、故障停止 |
| trading-engine/.../messaging/KafkaHintConsumer.java | 消费新 trade Topic 唤醒同一个追赶入口，不直接用消息体记账 |
| trading-engine/.../SecuritiesEngineApplication.java、config/EngineConfiguration.java | 根包 Boot 入口与显式装配、健康与低基数指标 |
| trading-engine/.../web/api/InternalTradingEngineApiController.java | 本机内部只读状态、账户、分证券活动订单查询；未就绪 503 |
| trading-sequencer/.../securities/sequencer/SequenceService.java | 消费新 sequence/transfer，事务提交后发提示并手工确认 offset |
| trading-sequencer/.../securities/sequencer/SecuritiesSequencerApplication.java | 独立定序入口和真实消费者健康状态 |
| 两模块 securities-*.yml | 不连接旧 Config Server，证券凭据必填，本机专用端口 |
| 两模块 pom.xml | 明确旧主类，另生成 -securities.jar，不让打包器随机选择入口 |
| build/securities/start-core.ps1 | 读取被忽略的本地凭据，前台启动一个证券应用，结束还原环境 |
| build/securities/test-phase2.ps1、test-database.sh | 自动建随机临时库、运行正式 Flyway、打包验收并精确清理 |
| dev.ps1、dev.sh | 新增 engine/sequencer 命令；不改变原 infra/status/stop/verify 语义 |
| 三个新引擎测试及 SecuritiesModelTest | 核心、协议、真实持久化和两个实际进程的强制终止恢复 |

实际 Java 包是 `com.itranswarp.securities.*`（不在旧 `com.itranswarp.exchange` 扫描范围内）。
新入口使用 `@SpringBootConfiguration + @EnableAutoConfiguration` 并显式声明所需 Bean，没有全包组件扫描。
所以本次不需要删掉或放宽第一阶段的 `LegacyStorageGuard`：旧应用仍然拒绝新库，新入口只装配新核心。
为保持最小闭环，冻结、撮合和清算先作为 SecuritiesEngine 的私有方法，统一维护事件原子边界，未复制一组互相调用但仍可被任意线程修改的 Spring 服务。

## 一、单事件如何原子地修改内存

核心 `execute` 的决定性顺序是：

```java
var candidate = state.copy();
ExecutionResult result = apply(candidate, event);
if (!result.accepted()) candidate = state.copy();
candidate.sequence = event.sequenceId();
validate(candidate);
state = candidate;
return mode == Mode.LIVE ? Optional.of(result) : Optional.empty();
```

`state.copy()` 深复制余额和订单；盘口与全局索引引用候选中的同一订单对象。
冻结、撮合、清算均发生在候选里；校验完整成功才一次替换 `state`。
余额不足、错误撤单等正常拒绝会丢弃候选资金变化，但仍消费事件位置，后续事件可继续。
断链、内部不变量异常或严重执行错误则标为失败，不安装半笔候选，也不允许再查询余额或保存快照。
`@Transactional` 不能回滚普通 Java 对象，所以这里没有把数据库事务注解当成内存原子性的保障。

代价是每事件 O(状态规模) 的复制与全量校验；这是五证券模拟系统当前明确取舍，不是高频生产撮合实现。
不采用每股票一个并行资金线程，因为五证券使用同一现金账户。

## 二、资金与股票如何守恒

`EngineState.cash` 是 userId→USD，`positions` 是 (userId,securityId)→股数。
每项都有 available/frozen。只读账户视图始终带 USD 和五证券，不暴露可变对象。
内部用户 1 是记账对手：普通用户配给 +100 USD 时内部现金 -100；配给 100 股 STAR 时内部 STAR -100。
普通用户不得负余额；内部对手只能非正可用额、零冻结，不能作为普通交易用户。
这是模拟记账，不代表真实银行存管、股权发行登记或允许用户借券。

下单：BUY 冻结 price×quantity 的共享 USD；SELL 冻结该股票 quantity。
核心与恢复均检查：

```text
用户 USD 冻结 = 该用户所有证券买单的 Σ(委托价 × 剩余股数)
用户某证券冻结 = 该用户该证券卖单的 Σ(剩余股数)
全部账户 USD 总和 = 0
每只证券全部账户股数总和 = 0
```

STAR 资金不足不能用 GREEN 持仓抵扣，STAR 买单和 GREEN 买单也不能分别花同一份 USD。

## 三、撮合清算和撤单

每证券有独立 Market：买盘高价优先、卖盘低价优先，同价按全局事件序号排序。
委托只进本证券盘口，清算再次检查 buyer.security==seller.security。
新订单 id 直接使用其全局事件序号，不沿用旧 BTC 包中依赖年月/时区的编号公式；重放相同事件得到同一标识。

`settle` 的关键计算：

```java
BigDecimal reserved = buyer.price.multiply(quantity);
BigDecimal paid = price.multiply(quantity);
cash.frozen(reserved.negate());
cash.available(reserved.subtract(paid));
next.cash(seller.user).available(paid);
position.frozen(quantity.negate());
next.position(buyer.user,buyer.security).available(quantity);
```

其中 price 为 Maker 价。卖单 100×4 先挂，买单 105×10 后到：

- 买方先冻结 1050。
- 成交支付 400，退回 20 的本次价差。
- 剩余 6 股仍冻结 630，不提前解冻。
- 撤余单再释放 630；若初始现金 10000，最终可用为 9600、冻结 0。

卖方主动吃已有高价买盘同样按 Maker 买价成交。
撤单检查用户、证券、订单仍活动，再解冻剩余部分，同时从盘口和活动索引移除。
当前没有手续费、做空、融资、撤改单、交易时段或自成交防护，不应宣传为完整现实证券交易所。

## 四、定序、幂等和 Kafka 故障窗口

`EventJournal.append` 使用 TransactionTemplate：

```text
校验和规范化命令
→ SELECT storage_metadata ... FOR UPDATE
→ 查询 unique_events 联接原事件
→ 已有相同用户/内容：返回原事件
→ 已有不同内容：拒绝
→ 分配新序号/前序号
→ INSERT events
→ INSERT unique_events
→ 一起提交
```

固定元数据行在空库也存在，可以真正串行化多个 append；不是用内存 AtomicLong 跨进程猜测位置。
金额先去掉无意义尾零，使 100.0 和 100.00 的相同重试等价。
事件读取还对照 SQL 列检查 JSON 的序号、前序、时间和版本，损坏记录不被接受。

定序器只从 `securities.v1.sequence` 接订单/撤单，从 `.transfer` 接内部配给。
普通订单 Topic 不能提交配给；但这仍是可信本机开发网络，不是生产鉴权，第三阶段公开 API 必须从认证上下文填写 userId，不能把这些内部命令直通给浏览器。
定序器确认 offset 之前先提交 MySQL，Kafka trade 提示发送失败只记录告警：事件已经持久化，不假装数据库可被撤销。
若数据库事务失败，停止后续确认；重启后同一请求重试由幂等表处理。
对格式错误的毒消息目前失败关闭，需要诊断，不实现自动跳过/DLQ 或静默丢弃。

引擎既响应 Kafka trade 唤醒，也每 200ms 通过同一同步入口追赶 MySQL 的已提交上界。
因此最后一个通知丢失、且没有下一条消息，也能发现日志尾部。Kafka 消费位置不是资产恢复位置。
多个信号不会并行记账；重复事件不再次执行，未知缺口必须按完整日志补齐或失败。
当前实时 ExecutionResult 尚未接历史投影/行情/私人推送；这些可靠输出及补偿属于第三阶段，不能把资金恢复正确等同于通知绝不丢失。

## 五、快照和恢复

格式 2 包含全局序号、目录版本与 SHA-256 规则指纹、所有现金/持仓、活动订单、五市场最近成交价和市场进度。
必须整体捕获，不能分五个时刻各取一只股票后拼接共享现金。
核心锁内复制不可变值，锁外执行 JSON 编码和 JDBC 写入；数值去尾零，列表排序保证相同状态有稳定文本。
当前运行协调器仍把事件执行与快照保存串行调度，因此 JDBC 保存期间会延后下一批执行；“核心锁不跨 I/O”不等于已经实现完全无阻塞的异步快照。
快照不用记录 Kafka offset、输出队列或瞬时时钟。
恢复按下列顺序：

```text
RECOVERING
→ 检查证券数据库/目录
→ 取得单引擎会话锁
→ 固定数据库已提交上界
→ 校验候选原文 SHA-256、版本、字段
→ 新候选引擎重建所有索引与盘口，不重新冻结、不重新撮合
→ 重放尾部并检查前序链
→ 保存启动恢复快照
→ 再追赶恢复期间提交的有限尾部，仍采用 REPLAY 模式
→ 有变化时保存最新完整状态
→ READY，开启定时追赶与周期快照
```

结构损坏的候选可以回退至较旧快照，无有效快照时全量重放；缺表/断库不能当成没有快照。
有效快照的规则指纹与当前目录不同则直接停止，不能悄悄按新规则计算旧账户。
第一版目录不支持运行中修改规则；无快照全量重放也要求保留原目录。未来正式变更目录需要版本化规则事件，不是手工 UPDATE。
默认 60 秒检查周期快照，有新事件才追加。保存失败不推进 lastSavedSequence，下轮重试；启动快照保存失败则不能 READY。
查询恢复中/FAILED 的账户立即报不可用；只读 HTTP 端点映射成 503，不能返回正常的 `{}`。

会话锁在每批追赶和保存前检查；另一实例同角色不能同时启动。锁断开会停止使用。
这不是具有强 fencing 的分布式选主，不支持生产多副本自动故障切换。事件日志和快照也不能替代备份。

## 启动和查看

不需要删除容器、清空数据或运行新 SQL。沿用第一阶段 V1，新核心需要的列已经存在；本阶段没有修改 V1、没有新增迁移。

```powershell
# 根目录，保留现有证券环境数据。
.\dev.ps1 infra
mvn -f build/pom.xml package

# 两个终端分别运行，Ctrl+C 只关闭本次应用。
.\dev.ps1 sequencer
.\dev.ps1 engine
```

独立包为 `trading-sequencer/target/trading-sequencer-securities.jar` 和 `trading-engine/target/trading-engine-securities.jar`。
不带 `-securities` 的包仍是旧 BTC 应用，不能连接新数据库。
Linux/macOS 使用 `bash dev.sh sequencer` / `bash dev.sh engine`；本次完整进程验收是在 Windows，Bash 仅做语法检查。

本机只读端点：

- `http://127.0.0.1:8102/internal/securities/status`
- `http://127.0.0.1:8102/internal/securities/accounts/1000`
- `http://127.0.0.1:8102/internal/securities/markets/1001`
- 引擎管理端口 18102，定序管理端口 18103，路径 `/actuator/health`。

引擎还导出 applied/target/ready 三个证券核心指标；旧监控 Compose 和 Grafana 仪表盘未自动改成新应用目标。
内部账户查询没有正式用户鉴权，仅绑定 127.0.0.1，不得公开映射到公网；浏览器业务访问和认证接入第三阶段处理。
空库首次启动会生成零序号一致性快照，不会初始化用户资金。查看 1000 用户全零不代表自动给该用户创建了登录账户。
当前旧 UI 看见的 BTC 数据不来自此新核心。

## 测试与验收

```powershell
.\build\securities\test-phase2.ps1
```

运行器自动创建 `exchange_securities_test_<32位随机标识>`，使用同一正式 V1 Flyway 脚本迁移，再打包、运行全模块 verify。
临时库账户权限限定该库，测试结束只删除该随机库和权限。
Kafka 测试 Topic/消费组使用 `securities.test.<随机标识>`，不使用正式 Topic 或旧消费组。真实进程日志放在系统临时目录，打印路径但不打印密码。
Windows 管道可能添加 CRLF，测试数据库脚本在容器里过滤 CR 后执行，不依赖外部手工改换行。

### 对应自动化用例

`SecuritiesCoreTest`：买方/卖方主动成交的五证券参数化用例、共享资金、不可跨证券成交、部分成交撤单、价差、价格时间优先、重复和业务拒绝、内部账户限制、损坏快照、断链以及固定种子的 200 条混合事件。
恢复对比遍历一组五证券历史的每个快照切点，比较全量执行和快照加增量执行的完整结果；恢复后还撤销原挂单。
重放返回 Optional.empty，实时返回不可变 ExecutionResult，因此不会进入尚未接入的外部输出路径。

`SecuritiesPersistenceTest`：真实行锁并发分配序号、同内容幂等和内容冲突、第二次 INSERT 故障时事务回滚、事件列/JSON 损坏检测、无 Kafka 提示追赶、恢复挂单继续撤销、实例互斥、快照校验和损坏回退、历史缺失失败、启动快照 INSERT 失败、周期有新事件才保存和阻塞恢复时查询立即拒绝。

`SecuritiesProcessTest`：启动真实两个新 jar，经真实 Kafka 配给和五证券成交；独立重放保存明确的 16 序号快照后再提交 17 序号挂单，直接 destroyForcibly 强制终止引擎。
重启后必须恢复 17 位置的冻结额且不重复冻结；重复配给不新增资金；恢复的原卖单继续成交、原买单成功撤销。
最后等待强制终止旧消费者的会话过期，再删除本测试组及 Topic。普通 stop 测试不能替代此强制终止用例。

Java 普通 verify 不会自动启用临时数据库用例，必须使用此运行器；不要把跳过的联调说成通过。
2026-10-27 最终实际执行结果：

- `test-phase2.ps1` 的全模块 verify 成功，Surefire 汇总 164 项：162 通过、0 失败、0 错误、2 个条件跳过占位项。
- 本阶段新增 31 项均通过：2 项协议、19 项核心、9 项真实持久化、1 项真实进程联调。
- 两个未开启的检查组为旧环境 LiveDependencyMonitoringTest 和第一阶段针对开发库的 SecuritiesDatabaseTest；不是本阶段临时库测试未执行。
- 强制终止恢复并继续成交/撤单的进程用例通过，不仅是正常 stop/restart。
- 已核对所有随机临时库、测试权限、测试 Topic 和测试消费组清理；开发库 users/events/engine_snapshots 仍为 0 行。
- V1 Flyway 脚本未修改，不要求补表、重置数据库或删除容器。
- PowerShell 启动/测试脚本语法检查通过；dev.sh 和测试数据库 Bash 脚本在容器内语法检查通过。

各模块原始测试报告在 `target/surefire-reports`。真实进程验收使用随机端口和临时库，不表示已替用户长期启动开发应用。

## 本阶段没有承诺的能力

- 股票版 UI、登录、正式用户交易 API、行情缓存、订阅和历史投影尚未接入。
- 不保证 Kafka/HTTP/通知跨系统恰好一次；核心通过日志幂等和恢复保障已提交状态可重建。
- 未做主机断电、Docker 数据卷损坏、MySQL 提交后持久化介质丢失或生产级容灾演练。
- 复制候选状态不是高性能方案，不支持多引擎同时服务资金。
- 不自动清理事件、快照；长期运行需要容量管理、备份和保留策略。
- 旧 BTC 环境保留，不自动迁移 BTC 资产到任意股票。

回退只需停止本次证券核心，旧代码和环境未被改造为证券数据；本次不执行 Git 提交/推送。
