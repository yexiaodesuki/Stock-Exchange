<!-- 六容器本地网关环境说明：保留数据，Java 通过根目录脚本或 IDEA 启动。 -->
# 运行与验证

## 文件与首次配置

`build` 顶层有一个 `docker-compose.yml` 和 Maven 的 `pom.xml`（另有隐藏的私密配置及其示例）。SQL 在 `build/sql/securities`，监控在 `build/runtime`，验收代码在 `build/securities`。根目录提供可选的 start.ps1 一键 Java 启动脚本；不提供根目录 Dockerfile、Compose 或旧 dev 脚本。

复制 `build/.env.securities.example` 为 `build/.env.securities`，填写独立随机值。Compose 不会自动生成密码。示例中的 `replace-with-` 都是占位值，不可直接使用。应用把此文件当 properties 读取，使用简单 `KEY=value` 格式，不加引号，不用 shell 表达式。

数据库密码不能通过改 env 直接重置已有 MySQL 用户；用户密码也不能通过改 env 重置已存在账户。原配置已搬入 build，并保留原值。不要公开 Compose 展开结果或私密配置。

## 启动基础设施

在 build 目录执行：

```powershell
docker compose --env-file .env.securities up -d --wait
docker compose --env-file .env.securities ps
```

六个固定容器名不能在同一个 Docker 引擎中重复。因此本配置面向一个开发环境，不支持同机直接并行启动第二套同名栈。已有手工 nginx 须先确认用途，启动脚本不会删除或接管它。

| 容器 | 宿主机端口 | 数据卷 |
|---|---|---|
| mysql | 13306 | stock-exchange-securities_mysql-data |
| redis | 16379 | stock-exchange-securities_redis-data |
| kafka | 19092 | stock-exchange-securities_kafka-data |
| grafana | 3000 | stock-exchange-monitoring_grafana-data |
| nginx | 8080 | 无业务数据卷，配置只读挂载 |
| prometheus | 9090 | stock-exchange-monitoring_prometheus-data |

全部发布到 127.0.0.1。保留既有卷名是为了保留证券数据和监控数据，Compose 对跨项目复用旧监控卷的标签警告不表示数据损坏。新克隆机器没有这些卷时会自动创建，不需要手工 docker volume create。

MySQL 只创建 exchange_securities 数据库和应用用户。证券目录加载入口 `SecurityCatalog.load` 先调用 `SchemaMigration.migrate`，使用 JAR 内打包的 V1/V2/V3 SQL；迁移完成才校验证券家族并创建业务服务。首次启动含快照表，不需要后来手动补表。Flyway 使用原历史表与数据库锁，并发启动可串行校验；版本校验失败拒绝启动，禁止用 repair、baseline 或清库绕过。

Kafka 主进程在同一个容器中受 shell 监督；就绪后执行幂等 Topic 初始化，四个 Topic 为 securities.v1.sequence / transfer / trade / tick，单分区、单副本、无限保留。初始化失败会使容器退出；只有 broker 和初始化都成功才报告健康。单副本只是开发环境，不是生产容灾。

## 一键启动 Java

先开启 Docker Desktop 并等引擎就绪，再在项目根目录执行；不必逐个手动开启容器：

```powershell
.\start.ps1
# 已打包且无需更新代码：
.\start.ps1 -SkipBuild
# 强制重新打包（先停止旧 Java 应用）：
.\start.ps1 -Rebuild
# 不打开浏览器：
.\start.ps1 -NoBrowser
```

支持 Windows PowerShell 5.1 和 PowerShell 7。脚本带 UTF-8 BOM，避免 5.1 将中文误读为其他编码。若本机执行策略拦截，可使用 powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1，仅作用于此次进程，不修改系统执行策略。

脚本读取本 Compose 项目六个容器的名称、运行状态和健康状态，不展开包含密码的配置。都健康时跳过 Compose up；否则执行 up -d --wait --wait-timeout 180 --no-recreate，补齐并等待，不重建已有容器或删除卷。Docker Desktop 不可连接时明确失败。运行但不健康的容器会超时失败，不擅自重启数据库。修改挂载的 Nginx 配置须验证并 reload；修改容器环境须单独确认并更新，不能依赖跳过逻辑自动重建。网关健康仅说明进程存活，Java 全就绪后还会通过网关检查 /session，随后打开 localhost:8080。网络接入、安全边界及网关隔离验收见 [Nginx 说明](nginx.md)。

打包输入包括九个模块及 build 的 POM、src/main 源码与资源，以及 common 打包使用的 build/sql/securities/V*.sql。不含外置 config-repo、私密配置和日志。已有构建第一次按文件时间判断是否可接管，之后在 build/.local-run/build-state.json 保存输入及七个 JAR 的内容指纹；未变化自动跳过，缺 JAR、输入变化、产物不匹配则运行 Maven clean package -DskipTests。这个打包步骤不代表执行了测试。-Rebuild 强制重建，-SkipBuild 强制使用旧产物，两者互斥。需要构建而旧 Java 应用仍运行时明确拒绝，请先停止它们，不能覆盖运行中的 JAR。

完成容器和构建检查后先启动 Config，检查 /securities-engine/default 的版本契约，再校验六份应用配置；全部通过才依次启动引擎、定序、行情、API、Push、UI。

引擎必须返回 READY，其他业务应用的真实健康接口必须返回 UP，不把端口开启当作启动完成。每个应用默认等待 120 秒，可用 -StartupTimeoutSeconds 240 调整。健康状态或配置检查失败会停止后续步骤，并只关闭本轮新建的后台进程，既有进程不受影响。

日志位于 build/.local-run/时间戳-随机标识/，本次新启动的应用有 out.log、err.log，执行 Maven 时有 build.log，processes.json 记录进程 ID、启动时间、JAR 和是否复用，不保存密码。复用应用的输出仍在最初那轮目录，不会迁移。无法确认归属的端口占用（包括 IDEA 启动实例）会拒绝，需自行选择一种启动方式。

脚本正常退出后应用继续后台运行；关闭终端不会停止它们。停止时在根目录执行 .\stop.ps1；如被执行策略阻止，使用 powershell -NoProfile -ExecutionPolicy Bypass -File .\stop.ps1。IDEA Stop 只管理 IDEA 启动的实例，不能停止脚本启动的实例。

stop.ps1 合并 build/.local-run 各轮 processes.json，避免最近一次启动记录不完整而漏停；按 UI、Push、API、行情、定序、引擎、Config 的顺序，核对固定应用名、本项目完整 JAR 路径、PID、启动时间和 Java 命令行后才终止。已退出进程安全跳过，PID 被重用或身份不符则保护跳过；无法解析的记录只警告、不猜测。脚本不含 Docker 操作，不停止容器、不删除卷或日志。不要与 start.ps1 同时执行。

Windows 下 Stop-Process 是进程终止，不能保证 JVM shutdown hook 或在途请求完成，不宣称优雅停机；重新启动继续使用现有事件和快照恢复机制。若运行记录丢失，脚本不会猜测并批量关闭 Java；请核对实际进程手动处理。停止逻辑隔离测试为 pwsh -File build/securities/test-stop.ps1。

停止脚本验收（2026-10-28）：已实际核对身份并逆序停止七个 Java 应用；Windows PowerShell 5.1 和 PowerShell 7 各 8 组测试通过，包括历史 JSON 数组、损坏记录、PID 重用、路径保护、停止顺序和重复执行。再次执行真实 stop.ps1 时七个记录均显示已退出；应用监听端口为零，五个 Docker 容器仍运行且健康。未通过重启业务服务来测试停止，结束时保持应用停止。

启动脚本隔离测试：pwsh -File build/securities/test-startup.ps1。它只加载函数，检查启动顺序、失败阻断、就绪响应、超时和归属，不创建业务数据。

本轮启动脚本验收（2026-10-28）：PowerShell 7、Windows PowerShell 5.1 各 8 组隔离测试通过；实际干净打包后按 Config 优先顺序启动七个应用，全部达到各自就绪条件；重复执行七个 PID 不变且均标为复用，并发起打开 UI 页面。user1、user2 的实际 HTTP 登录返回 200，用户 ID 保留 1000、1001；数据库仍为十位用户、105 条事件、18 条成交，没有重新配资。本轮未重跑完整交易回归，验证范围是启动脚本、健康检查及登录。

后续容器与构建缓存扩展：两种 PowerShell 各 13 组隔离测试通过，新增容器健康跳过、停止状态补齐、失败阻断、已有构建接管、双指纹匹配，以及缺失/变更后的重建判断。真实环境验证五容器健康时不执行 Compose up、已有构建不重复打包、Java 应用继续复用；未为了测试停用开发数据库，容器停止/缺失分支采用注入状态模拟验收。

## IDEA 启动 Java（仍然支持）

导入 build/pom.xml，使用 Java 17，加载 Maven。每个运行配置的工作目录设为项目根目录，例如 D:/Stock Exchange。入口现已统一到 com.itranswarp.exchange 根包；若 IDEA 旧配置仍指向 runtime、api、ui 等子包，请更改 Main class 或删除该旧运行配置后，从新入口左侧运行按钮重新创建配置，不需删除整个 .idea。旧 BTC 入口已删除；各模块使用标准 src/main/java 与 src/test/java，不需要手工设置包前缀。

顺序：

1. config：com.itranswarp.exchange.ConfigApplication，8888 / 18888。
2. trading-engine：com.itranswarp.exchange.SecuritiesEngineApplication，8102 / 18102。
3. trading-sequencer：SecuritiesSequencerApplication，8103 / 18103；quotation：SecuritiesQuotationApplication，8105 / 18105。
4. trading-api：SecuritiesApiApplication，8101 / 18101；push：SecuritiesPushApplication，8106。
5. ui：SecuritiesUIApplication，8100 / 18100。

应用本地 application.yml 自动导入 build/.env.securities，再强制导入 Config Server。配置服务不读取这个私密文件；只发布 config-repo 下的公共配置。不可用或返回错误版本标记时，客户端停止启动。配置修改后需重启对应客户端，尚无动态刷新总线。

UI 使用 SECURITIES_DEMO_ENABLED=true 时，新环境自动创建十个用户并经交易链配资和生成首批成交；已有 COMPLETE 的旧 demo-v1 两用户环境会保留原账户并补齐 user3@example.com 至 user10@example.com。新增每人 100000 USD 和五证券各 1000 股，经真实交易链执行，不创建额外订单或成交。补齐完成显示 LEGACY，重复执行不重复配资。普通注册仍不自动配资；修改 env 不重置既有账户密码。边界与验证见 [演示用户补齐](demo-users.md)。

## 监控

Prometheus：[Targets](http://localhost:9090/targets)，应看到六个证券 Java 应用。Push 是 Vert.x 进程，当前不提供 Prometheus 指标，健康检查见 http://localhost:8106/actuator/health。未启动的应用显示 DOWN 是事实，不是 Compose 创建失败。

Grafana：[证券总览](http://localhost:3000/d/securities-overview)，admin 使用原数据卷内的密码；首次创建卷时取 SEC_GRAFANA_PASSWORD。保留旧卷时更改 env 不等于重设 Grafana 密码。

看板涵盖抓取状态、引擎业务就绪、事件执行与目标进度、JVM 堆、HTTP 请求速率。它使用证券版实际指标，不将旧 BTC 输出线程指标套用于新引擎。旧 Grafana 卷中用户保存的历史看板不擅自删除，证券看板在 Securities 文件夹。

Java 业务及管理端口继续监听 127.0.0.1。当前 Windows Docker Desktop 已实测容器经 host.docker.internal 能访问本机管理接口；没有打开 0.0.0.0。其他平台若不支持这种连接，不能假称指标已抓取成功，需要单独配置安全网络接入。

up=1 只代表指标可抓取，不代表所有业务成功；引擎 ready=1 也不能代替真实交易测试。

## 日常维护与验证

在 build 中：

```powershell
docker compose --env-file .env.securities stop
docker compose --env-file .env.securities up -d --wait
docker compose --env-file .env.securities logs --tail 100 kafka
```

停止 Java 应用用 IDEA Stop。不要把 down -v、全局 prune、删除数据目录当作普通重启；容器可替换，但命名卷保存实际数据。

在项目根目录：

```powershell
mvn -f build/pom.xml test
node --test ui/src/test/js/domain.test.mjs
```

上面不自动开启依赖外部环境的测试。完整隔离回归使用 PowerShell 7：

```powershell
pwsh -File build/securities/test-phase2.ps1
```

运行器仅创建随机测试库与隔离消息/缓存资源，打包后启动临时配置服务及业务进程，结束清理自己创建的数据库。测试脚本不用于环境初始化。浏览器联调另需 npm --prefix ui ci 后运行 test-phase4.ps1；这些是可选开发测试依赖，不影响 IDEA 启动。

当前验收记录见 verification.md。旧阶段文档中的 dev 脚本、额外 Compose、全 Docker 业务镜像启动方式已被本页取代。
