<!-- 源码整理记录：删除 BTC 实现，保留证券行为、模块边界及持久协议，恢复标准 Maven 布局。 -->
# 证券版源码整理

> 本页记录 BTC 文件清理与标准目录恢复。随后已进一步恢复业务分层，最新文件职责及调用关系见 [业务分层说明](business-layering.md)；入口类、配置和持久协议保持不变。

日期：2026-10-28。本次不重写撮合清算算法，不修改数据库数据、事件协议、快照格式或已执行 SQL。

## 保留与删除

保留 common、config、trading-engine、trading-sequencer、trading-api、quotation、push、ui 八个 Java 模块，以及 parent、build/pom.xml。保留证券核心与恢复、持久输出、行情、鉴权、推送、页面和对应测试。

删除 193 个旧 BTC 文件：旧业务入口与实现、旧测试、旧消息/ORM/Redis/监控公共实现、旧模板、机器人、旧配置、旧监控文件和带删库行为的 schema.sql。Direction、JsonUtil、ByteUtil 仍是证券版依赖的通用实现，继续保留。旧版消费工厂守卫 LegacyStorageGuard 及 requireLegacy 分支已删除，证券存储协议校验仍保留。

删除前的源码备份为本机临时目录中的 stock-exchange-before-btc-cleanup-20261028-114656.zip；不包含私密 env、数据库卷或 Git 元数据，不能当作业务数据库备份。

## 标准目录与入口

六个业务模块原来把 sourceDirectory 指向 src/main/java/com/itranswarp/securities，IDEA 因此将 quotation 等相对目录解释为完整包名。现在删除 sourceDirectory、testSourceDirectory 和隔离旧代码的编译白名单，使用 Maven 默认的 src/main/java、src/test/java。

例如行情文件现在位于：

```text
quotation/src/main/java/com/itranswarp/exchange/quotation/
├── SecuritiesQuotationApplication.java
└── QuotationService.java
```

两份文件均声明 package com.itranswarp.exchange.quotation，POM 的 mainClass 同步为 com.itranswarp.exchange.SecuritiesQuotationApplication。

| 模块 | 当前完整入口类名 |
|---|---|
| config | com.itranswarp.exchange.ConfigApplication |
| trading-engine | com.itranswarp.exchange.SecuritiesEngineApplication |
| trading-sequencer | com.itranswarp.exchange.SecuritiesSequencerApplication |
| quotation | com.itranswarp.exchange.SecuritiesQuotationApplication |
| trading-api | com.itranswarp.exchange.SecuritiesApiApplication |
| push | com.itranswarp.exchange.SecuritiesPushApplication |
| ui | com.itranswarp.exchange.SecuritiesUIApplication |

共移动 47 个源代码、测试和配置文件。业务源码与测试的包前缀机械替换为 com.itranswarp.exchange；common 中的 exchange.securities 公共证券协议包保持原位。没有为了模仿旧类名而重新拆写已经验证的 SecuritiesEngine 和 TradingEngineService。

## 标准配置入口

每个业务模块只保留 src/main/resources/application.yml。main 不再设置 spring.config.name，直接使用 Spring Boot 默认加载规则：

```java
SpringApplication.run(SecuritiesQuotationApplication.class,args);
```

本地 application.yml 继续强制导入 Config Server，同时读取 build/.env.securities。敏感信息不移到公共配置仓库。六个 spring.application.name 仍是 securities-engine 等证券标识，远程配置文件仍是 securities-engine.yml 等，不影响既有应用名、端口或消息命名空间。

删除根目录旧配置后，把证券集中配置从 config-repo/securities 移到 config-repo，Config Server 默认路径和隔离测试运行器同步更新。这避免新克隆项目还需自行区分两套配置仓库。

## 数据兼容边界

build/sql/securities/V1、V2、V3 与改前备份逐份 SHA-256 比较一致。事件通过明确的 PLACE_ORDER、CANCEL_ORDER、CREDIT_CASH、CREDIT_POSITION 类型名称序列化，不依赖本次移动的 Java 包名。快照仍由公共值字段组成，不写 Java 全限定类名。

不删除证券卷、旧绑定数据目录、用户密码文件或 IDEA 设置；不自动重配资。保留 demo-v1 COMPLETE 的识别，是为了保护已经存在的证券演示账户，并不代表仍保留旧 BTC 交易实现。

## 回归与 IDEA 操作

新增 ProjectLayoutTest 三项检查：所有源码包声明与标准目录一致；每个业务模块只有一个可解析入口；每个模块使用唯一 application.yml，且公共配置仓库不再分两套。

在 IDEA 的 Maven 窗口执行 Reload All Maven Projects，等待同步完成。已有运行配置如仍指向 com.itranswarp.securities，须改为上表新入口；最简单是在新的 Application 文件 main 方法旁重新运行。工作目录仍为项目根目录，JDK 17。不要手工把深层包目录标记为 Sources Root，也不要给标准目录添加包前缀。

直接点击 IDEA 后的索引状态未代替用户观察；结构检查与 Maven 编译验证的是项目模型和代码，不代表已远程操作过 IDEA。

本轮实际验收：干净构建成功；隔离回归 82 条记录，81 通过、0 失败、0 错误，1 个直连开发库条件组跳过；前端纯逻辑 4 项通过。覆盖新配置路径、真实配置服务、三项布局检查、空库自动迁移、撮合清算、快照恢复、行情/API/WebSocket 联调、十用户初始化与中断/重启续跑。未重新执行浏览器视觉全套测试。

临时测试库已清理，五个开发容器仍 healthy；开发库保持 2 个用户、54 个事件、16 笔成交。未执行 Git 提交、推送或数据库迁移脚本来改写开发库。
