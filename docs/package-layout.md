<!-- 统一源码布局：参考 main 的入口、Service、业务包与 web 习惯，仅调整归属和装配，不改变证券协议或页面。 -->
# 统一目录结构与阅读顺序

## 约定

本轮参考本地 main 分支 bd9cfaa。复用其根包入口、Service 协调、业务包和 web 的组织习惯，但不恢复 BTC 实现，也不取消证券版的恢复、可靠输出和候选状态提交。

每个模块仍使用标准 src/main/java/com/itranswarp/exchange 源码根。七个应用入口全部位于该根包；六个业务应用的入口只启动 Spring 并显式导入专属 config/*Configuration。ConfigApplication 保留 @EnableConfigServer 和配置服务器自动装配，监控配置留在 config。

配置键、配置中心应用名、HTTP 路径、端口、数据库、事件内容、快照 JSON、Redis 键、Kafka Topic 及页面资源不变。build/runtime 是监控部署配置目录，不是被移除的 Java runtime 包，不应删除。

## 各模块固定位置

以下路径均相对模块的 com/itranswarp/exchange 源码包：

| 模块 | 启动入口 | 装配 | 业务、协调与接入 |
|---|---|---|---|
| config | ConfigApplication | config/ConfigMonitoringConfiguration | 配置中心沿用 Spring Cloud Config |
| trading-engine | SecuritiesEngineApplication | config/EngineConfiguration | TradingEngineService；assets、order、match、clearing、core；snapshot、messaging、web/api |
| trading-sequencer | SecuritiesSequencerApplication | config/SequencerConfiguration | sequencer/SequenceService、SequenceHandler |
| quotation | SecuritiesQuotationApplication | config/QuotationConfiguration | quotation/QuotationService、QuotationDbService、QuotationCacheService |
| trading-api | SecuritiesApiApplication | config/ApiConfiguration | service/*Service；web/api/TradingApiController；web/json/PlainDecimalSerializer |
| push | SecuritiesPushApplication | config/PushConfiguration | push/PushService；push/web/PushServer、PushVerticle |
| ui | SecuritiesUIApplication | config/UIConfiguration | ui/service；ui/web；ui/demo |

同类职责采用同一种归属，而不是要求每个应用复制同样的空文件夹。引擎协调服务仍位于根包，与 main 的 TradingEngineService 一致；定序、行情延续 main 的专用业务包。

## 主要迁移对照

| 原位置或名称 | 现在的位置或名称 |
|---|---|
| runtime/EngineRuntime | TradingEngineService |
| runtime/SnapshotRepository | snapshot/SnapshotRepository |
| runtime/KafkaHints | messaging/KafkaHintConsumer |
| runtime/SecuritiesEngineController | web/api/InternalTradingEngineApiController |
| sequencer/SequenceRuntime | sequencer/SequenceService |
| quotation/QuotationRuntime | quotation/QuotationService |
| api/SecuritiesApiController | web/api/TradingApiController |
| api/PlainDecimalSerializer | web/json/PlainDecimalSerializer |
| push/SecuritiesPush | push/web/PushServer |
| push/PushVerticle | push/web/PushVerticle |
| ui/SecuritiesWeb | ui/web/MvcController |
| ui/SecuritiesUsers | ui/service/UserService |
| ui/SessionService | ui/service/SessionService |
| ui/web/ApiProxyService | ui/service/ApiProxyService |
| ui/DemoBootstrap | ui/demo/DemoBootstrap |

六个业务入口原有的 Bean 方法迁入专属 Configuration；不是复制第二套装配。Maven mainClass 指向新入口，测试的 import、同包访问和源码路径检查同步迁移，没有保留旧入口包装类。

## 为什么这些变化不改变业务

入口现在采用：

```java
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(EngineConfiguration.class)
```

它不增加全根包组件扫描，避免因为入口移动而意外扫描其他配置。EngineConfiguration 仍显式导入内部控制器、创建同一批服务并注册相同生命周期。其启动次序保持恢复引擎、启动 Kafka 唤醒、启动持久输出发送器。Push 入口仍明确使用 WebApplicationType.NONE，不另启 Servlet 服务。

TradingEngineService.catchUp 的关键顺序不变：

```java
var prepared = engine.prepare(event);
outputs.save(event, prepared.output(),
        mode == SecuritiesEngine.Mode.REPLAY || event.sequenceId() <= recoveryUpper);
engine.install(prepared);
```

prepare 只生成候选；持久化成功后才 install；重放边界继续控制历史副作用。移动包、改类名不改变该事务与内存发布边界。

core 保留 EngineState、SecuritiesEngine、StateValidator、SnapshotCodec、SecuritiesSnapshot。SnapshotCodec 需要访问封闭候选状态，不能为了目录对称而扩大其可变状态访问权限；访问数据库的 SnapshotRepository 单独位于 snapshot，避免把持久化混进纯业务核心。

UI 只迁移后端服务、控制器和演示组件；过滤器优先级、代理白名单、CSRF、会话、密码哈希和演示配资标识不变。PushServer 负责监听与握手，PushService 负责 Redis 订阅，PushVerticle 负责会话分发，原关闭与订阅互斥不变。

## IDEA 与验证

重新加载 build/pom.xml。应用名称不变，但 Main class 全部改为 com.itranswarp.exchange 下的对应入口。例如引擎为 com.itranswarp.exchange.SecuritiesEngineApplication，配置中心为 com.itranswarp.exchange.ConfigApplication。

如果已有运行配置指向旧子包，在 Edit Configurations 修改 Main class，或从新入口重新创建该运行配置；无需删除整个 .idea。Working directory 仍是项目根目录。无需重建容器、重建表、重新配资或调整私密配置。

ProjectLayoutTest 新增两项约束：所有入口位于根包且只导入专属装配，以及各协调器/适配器位于约定路径且不再出现 Runtime.java。保留原有包路径匹配、唯一入口、配置中心导入检查。

BusinessLayeringTest 继续检查控制器不持有基础设施客户端、领域服务不混入 I/O，以及 prepare → outputs.save → install 顺序。LayeringBehaviorTest 继续验证同一批 42 个事件的输出、快照和盘口指纹。真实配置中心、恢复、交易、行情、WebSocket 和双用户浏览器操作通过隔离验收运行器验证，结果以本轮最终报告为准。

## 本轮实际验收（2026-10-28）

- test-phase4.ps1 完整通过：Java 96 条记录、95 通过、1 条按独立开关跳过，0 失败、0 错误；前端纯逻辑 4 项通过。
- 真实配置中心及业务进程通过启动、恢复、查询和交易验证。浏览器通过双身份、五证券 K线、切股隔离、Maker 价格清算、撤单、刷新幂等、重连，以及桌面和移动布局；最终稳定周期无持续错误。
- 七个可执行 JAR 的 Start-Class 均为根包新入口；源码包路径、唯一入口、专属装配和职责目录约束通过。
- 与重构前源码备份比较，16 个迁移类在规范化包名、类名、导入、注释、排版及等价字符串常量拼接后，类体标记序列一致；核心、领域服务、共享证券协议和 UI 资源共 35 个受保护文件逐份文本一致。此检查辅助证明结构迁移没有混入业务修改，不替代行为测试。
- 开发库全量数据转储指纹前后一致，仍是十位用户、105 条事件。验收使用随机测试库与隔离消息前缀，结束后清理测试进程及自身测试库，不重建开发容器或重置用户资产。
- 仅移除了三个已经为空的旧目录：引擎 runtime、API 主源码 api、API 测试源码 api。没有删除 build/runtime 的监控配置；未修改私密配置或执行 Git 提交、推送。

源码回退备份位于本机临时目录 stock-exchange-before-package-layout-20261028-165051.zip，包含本次涉及的源码、POM、测试与文档，不包含私密配置或数据库数据。无需因本次目录重构回滚数据库。
