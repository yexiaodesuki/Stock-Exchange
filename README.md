<!-- 启动入口：Docker 运行五项基础设施，Java 可由根目录脚本或 IDEA 启动。 -->
# Stock Exchange

Java 17 / Spring Boot 模拟证券交易所雏形，持续优化中。支持五只虚构股票兑 USD、共享资金账户、分证券持仓、撮合清算、事件与快照恢复、行情和推送。不用于真实资金交易。

## 启动

需要 Java 17、IDEA（导入 `build/pom.xml`）、Docker Desktop Linux 容器。

首次将 `build/.env.securities.example` 复制为同目录 `.env.securities`，填写数据库密码、32 字符以上签名密钥、Grafana 密码和十个用户密码，不能使用示例占位值。这个文件被 Git 忽略。

也可以手动先启动容器，在 `build` 目录执行：

```powershell
docker compose --env-file .env.securities up -d --wait
```

只有五个容器，名称固定：`mysql`、`redis`、`kafka`、`grafana`、`prometheus`。没有 Java 应用容器或迁移任务容器。

Docker Desktop 就绪并填写本地配置后，在项目根目录执行一键启动（容器可尚未开启）：

```powershell
.\start.ps1
```

脚本先检查五个基础容器，均运行且健康就跳过，否则用现有 Compose 创建缺少的容器、启动已停止的容器并等待健康，不重建已有容器或删除数据卷。然后按需打包，先验证 Config 的六份客户端配置，再逐个等待其他应用就绪，全部成功后自动打开 UI。支持 Windows PowerShell 5.1 / PowerShell 7。若执行策略阻止脚本，可仅对本次调用使用 `powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1`，无需更改系统策略。

已有 JAR 且源码、资源、POM、打包 SQL 未变化时自动跳过 Maven；首次接管已有产物先核对时间，之后记录输入和 JAR 双指纹。`-Rebuild` 强制干净重建；`-SkipBuild` 显式使用现有 JAR，不保证与源码一致；`-NoBrowser` 不打开浏览器。日志、构建指纹和进程记录在被 Git 忽略的 `build/.local-run/`。重复执行复用本项目 JAR 进程；若需要重建但 Java 仍运行则拒绝覆盖，请先停止旧应用。未知端口占用（包括无法确认归属的 IDEA 实例）会明确报错，不自动停止。

停止脚本启动的 Java 应用，在根目录执行下方命令。它按依赖逆序检查历史启动记录，核对 PID、启动时间与本项目 JAR 后才终止，重复执行安全；不停止 Docker、不删除数据或日志。

```powershell
.\stop.ps1
```

Windows 下使用进程终止，不保证 JVM 优雅关闭；不要把批量结束所有 java.exe 当作替代方法。IDEA 启动的实例仍用 IDEA Stop。

也可以继续使用 IDEA；所有应用的 Working directory 均为项目根目录，依次运行：

1. `ConfigApplication`。
2. `SecuritiesEngineApplication`。
3. `SecuritiesSequencerApplication`、`SecuritiesQuotationApplication`。
4. `SecuritiesApiApplication`、`SecuritiesPushApplication`。
5. `SecuritiesUIApplication`。

业务应用直接读取 `build/.env.securities`，再从 Config Server 读取公共配置；无需在启动脚本中导出密码。MySQL 容器创建数据库，Java 应用首次连接时自动执行全部 Flyway 迁移（含快照表），重复启动只校验版本。Kafka 在自己的容器内创建并校验 Topic。

页面：[localhost:8100](http://localhost:8100)；Grafana：[localhost:3000](http://localhost:3000)；Prometheus：[localhost:9090](http://localhost:9090)。

## 账户与数据

全新环境设置 `SECURITIES_DEMO_ENABLED=true` 后自动创建 `user1@example.com` 至 `user10@example.com`，名称“用户一”至“用户十”，密码分别取配置中的十个用户密码。初始化通过真实事件链配资、挂单、撮合，可重复启动且不会重复配资。

旧两用户演示环境启用演示初始化后，默认保留 `buyer@example.com`、`seller@example.com` 并补齐 `user3@example.com` 至 `user10@example.com`；新增用户各有 100000 USD 和五证券各 1000 股。另行明确授权后可仅迁移原两人的登录邮箱和密码，不变更用户 ID、资产及交易记录；本机已完成该维护，现使用 user1 至 user10 登录。普通重启不会重置密码或重复配资，详见 [演示用户补齐](docs/demo-users.md)。容器和数据卷不是同一个东西；日常停止使用 `docker compose --env-file .env.securities stop`，不要删除数据卷。

## 模块与配置

交易链：UI → API → Kafka → 定序 → MySQL 事件 → 引擎撮合清算 → 行情 / 推送 → 页面。引擎以一致性快照加事件增量重放恢复。

- `common`：证券协议、目录、自动迁移、持久输出与缓存。
- `config / config-repo`：配置服务和非敏感公共 / 应用配置。
- `trading-engine / trading-sequencer`：资金、持仓、订单、撮合清算、恢复和定序。
- `trading-api / quotation / push / ui`：接口、行情、WebSocket 和页面。
- `build`：唯一 Compose、Maven 聚合入口，以及 SQL、监控和测试配套目录。

旧 BTC 实现已移除；各模块使用标准 Maven 源码目录，业务代码统一在 com.itranswarp.exchange 包下。启动入口统一位于模块根包，装配放 config，运行协调使用 Service，接口放 web；完整目录约定见 [统一目录结构](docs/package-layout.md)。各应用本地引导为 src/main/resources/application.yml；保留通用工具和现有证券交易实现。

阅读代码可从 [业务分层与调用关系](docs/business-layering.md) 开始：引擎按资产、订单、撮合、清算分工，API 使用 Controller → Service，后台协调器只负责运行流程。UI 页面保持原样。

## 监控与验证

Grafana 使用 admin 和本地配置的密码，打开 Securities 文件夹内的“证券交易所 - 系统总览”。只有启动对应 Java 应用后，Prometheus 的该应用 Target 才会变为 UP。Java 管理端口保持本机监听；Docker Desktop 使用 `host.docker.internal` 访问，不开放局域网管理入口。其他平台可能需要额外的本机网络接入配置。

```powershell
mvn -f build/pom.xml test
```

这只代表可运行的 Maven 测试，不等于完整联调。隔离数据库、恢复及真实进程联调方式见 [运行与验证说明](docs/running.md)。测试脚本仅用于验收，不参与环境启动。

后续继续完善监控、网关、可靠性和受控 AI 辅助能力；当前不具备生产级交易所保障。
