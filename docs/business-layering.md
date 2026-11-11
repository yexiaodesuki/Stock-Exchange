<!-- 证券版分层重构记录：只迁移职责，不改变交易、恢复、HTTP、消息与页面行为。 -->
# 业务分层与阅读顺序

当前文件位置以 [统一目录结构](package-layout.md) 为准：本页最初记录业务拆分，后续又统一了启动入口、Service、装配与 web 目录。

## 本轮边界

按原项目的业务职责恢复 assets、order、match、clearing、service、quotation、push、ui/web 等分工。保留当前证券模型、候选状态提交和可靠输出，未恢复旧 BTC 业务。应用入口、配置键、端口、SQL、事件类型、快照 JSON、Redis 键、Kafka Topic、HTTP 路由和错误状态保持不变。

没有改 HTML、CSS、JavaScript、图表或页面交互。UI 后端仅拆分安全、代理与会话辅助；六个 UI 资源文件与改前备份逐一 SHA-256 比较一致。

## 引擎：按业务阅读

```text
TradingEngineService                  恢复、追赶、快照时机、就绪、提交边界
  → core/SecuritiesEngine              分发事件，准备/校验/安装同一候选
      → assets/AssetService            配资、共享 USD 与分证券持仓冻结
      → order/OrderService             校验、创建、撤单、订单结果转换
          → match/MatchEngine          价格时间优先与 Maker 价撮合
              → clearing/ClearingService  成交价差和冻结资产清算
      → core/StateValidator            守恒、精度、冻结及索引一致性
      → core/SnapshotCodec             稳定快照值的编码和候选恢复
```

所有领域服务都只操作同一个 EngineState 候选，不保存自己的全局余额，也不启动线程或访问数据库。EngineState 的构造器、索引仍限制在核心包；跨领域服务只取得必要的候选余额、订单和盘口访问方法，核心不会通过查询或 Prepared 暴露这个可变对象。

OrderService.createOrder 先校验并调用 AssetService.tryFreeze，再创建订单并交给 MatchEngine.processOrder。MatchEngine 每笔成交仍在原位置调用 ClearingService.clearMatchResult。OrderService.cancelOrder 校验身份和证券后委托 clearCancelOrder 解冻，再移除两个订单索引。没有把多步骤操作拆成分别提交的事务。

运行时仍严格执行：

```java
var prepared = engine.prepare(event);
outputs.save(event, prepared.output(),
        mode == SecuritiesEngine.Mode.REPLAY || event.sequenceId() <= recoveryUpper);
engine.install(prepared);
```

实际重放边界条件仍在 TradingEngineService.catchUp 中计算，未替换成固定值。持久化失败不能先发布内存资金变化。业务拒绝仍消耗事件位置，重复事件、恢复失败和历史副作用隔离均沿用原逻辑。

StateValidator 与 SnapshotCodec 留在 core 包以访问封闭的候选索引；snapshot 包内的数据库快照仓储 SnapshotRepository 和公共持久输出 OutputStore / OutboxPublisher 不重复实现。TradingEngineService 是协调器，不重新承载已拆出的领域业务。

## API：Controller → Service

TradingApiController 只保留路由、参数/DTO、调用服务和原异常响应。SecuritiesApiApplication 用 @Bean 显式注入服务，不在 Controller 创建数据库或 Kafka 客户端。

| Service | 实际迁入职责 |
|---|---|
| ApiAuthenticationService | Bearer 签名验证、本人 WebSocket 票据 |
| TradingEngineApiProxyService | 只读 HTTP、超时、503、精确金额 JSON |
| HistoryService | 最终订单、订单归属和私人成交明细 |
| MarketQueryService | 盘口/成交/K线，缓存版本校验和回源 |
| SendEventService | 幂等登记事务、Kafka 提交、请求结果查询 |

SendEventService 独占生产者，Spring 只销毁它一次；Controller 不负责网络资源关闭。请求 ID 的用户绑定、内容哈希、202 与 SUBMISSION_UNCERTAIN 响应均保持不变。行情仍允许缓存降级，账户仍回源就绪引擎。代理继续启用 USE_BIG_DECIMAL_FOR_FLOATS，不能因拆类损失金额精度。

## 定序与行情

SequenceService 保留消费线程、实例锁、错误停止和 offset 确认；SequenceHandler 执行 Topic/命令来源校验，再交 EventJournal 完成定序、内容幂等和入库。失败仍向消费层传播，不确认未完成的消息。

QuotationService 保留锁、启动上界、定时任务与健康状态：

```java
lease.check();
var changed = database.catchUp(startupUpper, boot);
publisher.refresh(changed);
ready = true;
```

QuotationDbService.catchUp 从持久输出推进 MarketStore 事务投影；QuotationCacheService.refresh 按证券原子更新缓存并发布本次实时行情。异常仍由协调器把 ready 设为 false 并下轮重试。启动补齐的历史成交不被当作新消息广播。

## Push

- PushServer：HTTP/WebSocket 监听、Origin/票据握手与总体启停协调。
- PushService：Redis 订阅、重连、订阅确认和就绪；关闭与订阅共用锁，保留原互斥边界。
- PushVerticle：会话、订阅集合、用户/证券路由、心跳、背压、重拉通知。它是会话分发组件，没有新增第二个 Vert.x 实例或 Redis 消费者。

关闭顺序仍为停止重试、关闭会话/心跳、关闭 Redis 订阅、关闭 HTTP 与 Vert.x。私人消息与证券订阅是两种独立过滤，不因切股漏掉自己的其他证券通知。

## UI 后端与配置中心

MvcController 保留页面、登录/注册/注销控制器和原过滤器注册兼容入口。ui/web/ApiProxyService 保留白名单、会话身份、签名代理、超时和状态码；ui/web/UISecurityFilter 保留 CSP、CSRF 和请求大小限制；SessionService 保留会话 CSRF 和原 IP 限流。用户服务现为 ui/service/UserService，初始化现为 ui/demo/DemoBootstrap；页面资源不修改。

ConfigApplication 与 ConfigMonitoringConfiguration 已有清晰职责，本次不作无意义拆类。持久化公共组件也保留一份，不为模仿旧目录而重新写 ORM 或第二套记账。

## 验证方法与范围

- LayeringBehaviorTest：先在重构前固定 42 个事件，每步记录完整执行输出、快照和五证券盘口，基准 SHA-256 为 cbcd44c2ad9f32b4e95952c22c0b34f5b6633134add4c6c03a06978d78f5e9b1。重构后必须逐步序列化得到同一指纹，并检查重放与重复行为。
- BusinessLayeringTest：检查 Controller 不持有基础设施客户端，领域服务不混入 I/O，提交顺序不变，推送和行情职责独立。
- ApiServiceTest：临时 HTTP 服务验证精确小数、503 映射；签名验证拒绝无效身份。
- UiLayeringTest：同会话 CSRF 稳定、不同会话隔离，保持第十六次拒绝的原限流规则。
- test-phase4.ps1：隔离库、真实 Config Server、六业务进程、快照/交易/行情/API/WebSocket，以及双浏览器、撤单、刷新幂等、重连和响应布局回归。

基准与自动化测试验证的是所覆盖行为，不构成所有故障场景或生产资金安全的证明。测试只清理自身的随机库、Topic、缓存和进程，不停止用户 IDEA 应用。正常启动方式不变，不需要新 SQL、环境变量或容器。

## 本轮实际验收（2026-10-28）

- 重构前的固定历史基准与最终代码指纹一致；持久事件协议、ExecutionEnvelope 和 SecuritiesSnapshot 文件逐份哈希比对未变。
- 最终干净打包及隔离全量回归：90 条记录，89 通过、0 失败、0 错误，1 个直连开发库条件组跳过。
- 前端纯逻辑 4 项通过；真实浏览器双身份、五证券 K线、切股隔离、Maker 价格清算、撤单、刷新幂等、重连、桌面与移动布局通过，最终稳定轮询无持续错误。
- 补齐 Push 关闭与重新订阅的原互斥边界后，已对最终代码再次完整执行上述回归，而不只依赖修改前结果。
- UI 六个资源文件未变化；未修改 Compose、私密配置、SQL 或用户数据，未停止用户 IDEA 应用，未执行 Git 提交或推送。

源码备份位于本机临时目录 stock-exchange-before-layering-20261028-122840.zip；只用于代码回退，不包含私密配置或数据库数据。当前文件结构新增 23 个 Java 文件，修改 8 个既有 Java 文件；配置服务和通用持久化组件未作重复拆分。
