<!-- 证券改造第一阶段的实施说明、真实验收记录和边界，不将基础设施就绪称为证券交易完成。 -->
# 第一阶段：证券模型与隔离环境

> 历史实现记录：本页的 dev 启动脚本和旧 Compose 已移除。当前启动及验证以 [running.md](running.md) 为准，勿执行本页旧环境命令。

## 目标与现状

本阶段建立能独立初始化、重复启动、验证的证券基础设施。旧 BTC/USD 应用保留，不连接新库。
第一版只做虚拟证券、USD 现金结算、整股限价单，不做融券、期货、外汇、碎股、手续费、交易日历或真实交易所结算制度。
以下名称均为模拟标识，不对应外部同名证券，也不导入真实市场价格。

| 标识 | 代码 | 名称 |
| --- | --- | --- |
| 1001 | STAR | 星河科技 |
| 1002 | GREEN | 青山能源 |
| 1003 | CARE | 安康医药 |
| 1004 | DAILY | 优选消费 |
| 1005 | SAIL | 远航工业 |

只在 `build/sql/securities/V1__securities_schema.sql` 维护生产初始目录，Java 不维护第二份股票名单。
一份共享 USD 现金账户和五种持仓的执行、冻结、清算与快照属于第二阶段；目前没有发放资产。

## 目录与代码流

所有新增 Java 源码位于 `common/src/main/java/com/itranswarp/exchange/securities`，暂不接到旧交易入口。

| 文件 | 本次实际职责 |
| --- | --- |
| Currency.java / SecurityStatus.java | USD 结算币种；可交易/停牌状态 |
| SecurityDefinition.java | 不可变证券元数据与委托步长、范围校验 |
| SecurityCatalog.java | 从已迁移数据库加载只读目录，按标识查询；未知证券拒绝 |
| SecuritiesProtocol.java | 独立的命令、定序事件版本，以及 Redis/Topic/消费组命名 |
| StorageCompatibility.java | 数据库存储家族、协议与目录版本检查 |
| LegacyStorageGuard.java | Spring 数据源和旧 Kafka 工厂初始化阶段的混库保护 |

### 1. 规则从哪里来

未来证券应用调用 `SecurityCatalog.load(dataSource)`，先检查存储版本，再读 `securities`：

```java
StorageCompatibility.requireSecurities(source);
```

每行构造一个 `SecurityDefinition`。`record` 自动生成字段访问方法、相等比较等，但字段不可重新赋值。
紧凑构造器仍验证配置：USD、0.01 价格步长、1 股数量步长、目录规则版本 1。
目录拒绝重复标识、重复代码和空集合；`require(未知标识)` 不回退到 STAR。

委托验证关键条件（来自 `requireStep`）：

```java
if (value == null || value.signum() <= 0 || value.precision() > 36
        || value.scale() < -18 || value.scale() > 18 || value.compareTo(maximum) > 0
        || value.remainder(step).signum() != 0)
    throw new IllegalArgumentException(field + "必须为范围内的正数且符合步长");
```

`remainder` 检查步长整除，不使用浮点数，也不沿用旧请求的 `RoundingMode.DOWN`。
25.30 USD × 10 股有效；25.301 USD 或 0.1 股直接拒绝，不替用户改变委托。
单笔价格上限 9,999,999,999.99 USD，股数上限 1,000,000,000；这是模拟系统显式技术边界，不是现实交易所规则。
验证只读规则，不修改任何现金、持仓、Redis 或 Kafka。

### 2. 协议和版本

`Command` 是密封接口，只允许四种指定命令：`PlaceOrder`、`CancelOrder`、`CreditCash`、`CreditPosition`。
JSON 使用 `PLACE_ORDER` 等固定类型名，不允许任意 Java 类名反序列化。
定序之后的 `SequencedEvent` 带协议版本 2、目录版本 1、前序号、序号、唯一标识、服务端用户身份和命令。
唯一标识限制为 100 字符以内的 ASCII 字母、数字、点、下划线、冒号、连字符，与 SQL 幂等键一致。

```java
public record PlaceOrder(long securityId, Direction direction,
                         BigDecimal price, BigDecimal quantity) implements Command
```

`PlaceOrder` 构造器只检查必填结构，执行前还必须调用 `validate(catalog)` 才完成交易规则验证。
前序号允许非连续数值，但必须小于当前序号；实际链条连续性、用户归属、资金和重复执行检查在第二阶段实现。
公开 HTTP 接口尚未接入，因此这里的入账命令不是向用户开放的充值接口；未来身份从认证上下文取得，不能信任客户端的 userId。
此协议不继承旧 `AbstractMessage`，不被旧 `MessageTypes` 注册；当前没有证券消息生产者或消费者。
行情、订单输出 DTO 在第三阶段随真实执行结果接入，本阶段不虚构已可调用的行情 API。

命名示例：

- Kafka：`securities.v1.sequence / transfer / trade / tick`（均为完整前缀后的 Topic）。
- 消费组：`securities.v1.engine` 等稳定逻辑名称，不按容器 IP 命名。
- Redis：`securities.v1:1001:orderbook`、`securities.v1:1002:bars:min`。
- 行情频道：`securities.v1:market:1001`；私人通知以后按用户分发，不等同于行情订阅。

第一版保留全局单分区交易顺序，五只股票共用 USD，不能简单开启五个并行撮合线程同时改同一现金账户。
命名空间 `v1` 表示证券系统这一代存储家族，协议/快照 `2` 区分旧 BTC 格式，两者不是同一个版本号。

### 3. 为什么旧应用不能直接连新库

`LegacyStorageGuard` 是 Spring `BeanPostProcessor`，数据源初始化完便调用 `requireLegacy`。
检测到 `storage_metadata` 立即失败；未标记的旧库保留原有行为。
只在 ApplicationRunner 检查太晚：旧消费者可能已经在 `@PostConstruct` 中启动。
因此 Kafka 工厂也有提前保护：

```java
if (bean instanceof MessagingFactory)
    StorageCompatibility.requireLegacy(beanFactory.getBean(DataSource.class));
```

这会在旧 Kafka 工厂初始化、创建旧 Topic 之前强制创建和检查数据源。
守卫只读，不创建/删除表、不修复版本、不自动映射 BTC 为 STAR。
第二阶段接入证券应用时需要把旧守卫调整为显式版本化启动路径，不能只删除检查强行运行。

## 完整初始数据库

新 MySQL 自动创建 `exchange_securities` 空数据库，Flyway 独立任务执行 V1：

| 分组 | 表 |
| --- | --- |
| 证券与版本 | securities、storage_metadata |
| 用户认证 | users、user_profiles、password_auths、api_key_auths |
| 权威持久状态 | events、unique_events、engine_snapshots |
| 历史投影与入账记录 | orders、match_details、clearings、transfer_logs |
| 行情 | ticks、sec_bars、min_bars、hour_bars、day_bars |
| 演示初始化进度 | bootstrap_runs |

共 19 张表，另有 Flyway 自己的 `flyway_schema_history`。
不添加独立权威余额表：共享现金、分证券持仓和活动订单以后一起进入一致性快照，并由有序事件重建。
否则逐表更新余额与快照会形成两套可能互相矛盾的资金真相。
历史表/行情表是查询投影，存在不等于保证异步输出永不丢失。

证券相关订单、成交、清算、行情都带 `securityId` 和相应索引/外键。
K线使用以下结构而不是旧版单独 `PRIMARY KEY(startTime)`：

```sql
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
securityId BIGINT NOT NULL,
startTime BIGINT NOT NULL,
UNIQUE KEY UNI_BAR(securityId,startTime)
```

同一分钟允许五只股票各一行，但 STAR 同一分钟不能重复；单代理主键适配当前 ORM 的单 `@Id` 限制。
`CREATE TABLE LIKE` 不复制外键，V1 已对 min/hour/day 三表显式补齐。
`transfer_logs` 通过 CHECK 区分美元和证券，不能同时填 USD 和 securityId；整股、价格步长也有数据库约束。
事件协议版本、快照格式、目录版本均显式保存，旧事件和旧快照插入新库会被拒绝。
快照表已建好，但新格式编码与首次快照保存要在第二阶段实现，不能让旧引擎插入一份冒充多证券的快照。

## 自动迁移和启动顺序

```text
dev.ps1 infra / dev.sh infra
  → 检查 Docker 与 Compose 配置
  → 首次生成本地 .env.securities；已有卷而配置丢失则停止
  → 独立 MySQL / Redis / Kafka 健康
  → Flyway migrate 成功
  → 四个 Topic 幂等创建并核对单分区、单副本、保留策略
  → 只读检查表、目录、版本和 Redis PONG
  → 报告“基础设施就绪”，不报告“交易应用就绪”
```

`dev.ps1` 对每个原生命令检查退出码，任一步失败立即停止。
Compose 本身也定义 `service_healthy` 和 `service_completed_successfully` 依赖。
SQL 只读挂载到专用 Flyway 任务，不挂到 MySQL 的首次初始化目录，因此每次入口启动都能检查并应用新增迁移。
重复运行 V1 不重复插入五只证券；后续只增加 V2/V3，不改已执行 V1，也不手工运行零散补表脚本。
禁止使用 Flyway repair 掩盖校验和变化；MySQL DDL 不是整体可回滚事务，真实迁移失败需要先诊断，不能承诺失败自动还原全部结构。
旧 `build/sql/schema.sql` 保留给旧环境，新入口绝不执行它，更不执行其中的 DROP DATABASE。

## 环境隔离与日常命令

| 项目 | 原环境 | 新证券环境 |
| --- | --- | --- |
| MySQL 宿主端口 | 3306 | 13306 |
| Redis 宿主端口 | 6379 | 16379 |
| Kafka 宿主端口 | 9092 | 19092 |
| 数据库 | exchange | exchange_securities |
| Compose 项目 | 保持原状 | stock-exchange-securities |

证券环境使用项目专属的 mysql-data/redis-data/kafka-data **命名卷**，不挂载旧 `build/docker/*-data`。
实际卷名含 `stock-exchange-securities_` 前缀；Docker 管理其存储，不是仓库中的普通文件夹。
旧 Grafana/Prometheus 容器与其数据卷不变；本阶段未给它们接入不存在的证券应用指标。
新服务只绑定宿主回环地址，这是本地开发环境，内部 Redis/Kafka 未启用生产鉴权。
端口冲突会由 Docker 返回错误；需要时在本机 `.env.securities` 修改端口后重新执行，不停止抢占端口的其他服务。

```powershell
# 项目根目录；已执行过也可再次执行，保留数据。
.\dev.ps1 infra
.\dev.ps1 status
.\dev.ps1 verify

# 只停止证券容器，保留三个数据卷和所有旧环境容器。
.\dev.ps1 stop
```

Linux/macOS 用 `bash dev.sh infra|status|verify|stop`。本次只在 Windows 完整运行入口，Bash 做了容器内语法检查，未宣称已在 Linux 完整联调。
首次运行下载镜像可能较慢；当前固定 mysql:8.0.45、redis:7.4.2-alpine、apache/kafka:3.9.0、flyway/flyway:10.20.1，不自动追随 latest。
`.env.securities` 含随机密码，已被现有 Git 规则忽略；丢失后已有数据卷不会自动换密码，须恢复配置。
`.env.example` 可提交，但不能把真实密码放进去。`.gitattributes` 保证 SQL/Bash/YAML 的 LF 换行，防止跨系统执行失败。
IDEA 未来访问使用 127.0.0.1 加上述宿主端口；容器内部使用 mysql:3306、redis:6379、kafka:29092，不能使用容器自己的 localhost。
本阶段不提供可让旧应用误连新库的启用 profile。Push 的旧 Redis 配置字段、浏览器 WebSocket 地址等将在对应接入阶段修改。

查看新库（密码只在容器内部从环境取得）：

```powershell
docker compose --env-file .env.securities -p stock-exchange-securities -f build/docker-compose.securities.yml exec mysql bash -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -usecurities exchange_securities'
```

然后执行 `SHOW TABLES;`、`SELECT * FROM securities;`、`SELECT * FROM flyway_schema_history;`。

## 自动化测试及本次验收

### Java 测试

`SecuritiesModelTest`：合法委托不改值；碎股、错价步长、上下界、未知/停牌、重复目录、非法规则、四种命令序列化、旧版本和命名空间。
`SecuritiesDatabaseTest`：真实 MySQL 加载五证券；旧应用/旧 Kafka 工厂被阻止；错误目录版本拒绝；四类 K线的多证券唯一性及外键；旧事件/快照格式拒绝；幂等键重复拒绝；错误资金类别拒绝。
测试插入使用同一连接事务并回滚；错误版本也回滚，不保留模拟交易。

普通 `mvn -f build/pom.xml verify` 不会自动开启真实数据库测试。
有隔离环境后执行专用入口，会读取本机配置并在结束后还原进程环境变量：

```powershell
.\build\securities\test-java.ps1
```

它明确关闭旧环境的可选监控联调，只开启证券 MySQL 集成；不启动任何业务应用。

### 环境验收

```powershell
# 会短暂停止并启动证券容器；仅写入随机命名的测试键/Topic，不触碰旧环境。
.\build\securities\verify-phase1.ps1 -Restart
```

重复 infra 不改变目录、事件、快照和 Flyway 校验和。
错误 V1 夹具仅挂到单次 validate 任务，必须以非零退出码失败；不实际执行错误 SQL，不改真实 V1、不 repair。
正常校验随后仍成功。
Redis 写一个测试键，Kafka 写一个独立验收 Topic，然后 stop/infra，分别读回原值，验证保留卷重启。
最终只删本次随机测试键、消费组和 Topic，数据卷及四个业务 Topic 不删除。
如果环境故障导致清理失败，脚本会报告确切测试资源名称，禁止用全局清空替代清理。

2026-10-27 已实际完成：

- 从空的证券命名卷启动并自动执行 V1；19 张表、五证券和四 Topic 验收通过。
- 重复启动、错误校验和拒绝、正常校验恢复、保留卷重启后的 MySQL/Redis/Kafka 持久化验收通过。
- 新增 18 项模型/协议用例、5 项真实数据库用例全部通过；专用 test-java.ps1 的全模块 verify 成功。
- 最终 surefire 汇总：137 项，136 项通过，0 失败、0 错误、1 项跳过；跳过项为明确关闭的旧环境 LiveDependencyMonitoringTest，并非证券库测试。
- 检查测试资源已清理；新库 users/events/engine_snapshots/min_bars 均为 0 行。
- 原 mysql/redis/kafka 与原 Grafana/Prometheus 未删除、未重建、未停止。

这里的重启测试是正常 stop，不是突然断电、强制终止或生产故障演练。
Kafka 无限保留避免开发阶段事件提前过期，但会持续占用磁盘，需监测容量；不能当作备份策略。

## 剩余阶段与回退

第二阶段：共享现金、分证券持仓、按证券撮合清算、格式 2 多证券快照与恢复；统一调整存储守卫和生命周期。
第三阶段：实际落库投影、Redis Lua、查询和订阅按证券隔离，补输出协议。
第四阶段：证券选择 UI、共享资金/持仓页、可幂等重试的演示入账与真实模拟撮合初始化。
第五阶段：完整新克隆、一键应用启动、多证券交易和保留数据恢复的端到端验收。

当前可以停止新证券环境并继续使用原环境，无需回滚旧库，因为从未迁移它。
源码不含证券环境密码或数据卷，不会自动提交 Git；AGENTS.md 继续忽略。
不能在此阶段把旧 UI 显示的 BTC 余额认作新证券账户，也不能宣称已实现真实股票交易所。
