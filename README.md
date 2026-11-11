<!-- 项目首页：介绍模拟证券交易、首次启动、可选监控与知识问答。 -->
# Stock Exchange

一个使用 Java 17、Spring Boot 和 Spring Cloud Config 构建的模拟证券交易系统。五只虚构股票共用一个 USD 资金账户，支持从注册、模拟入金、限价委托到撮合、清算和行情推送的完整演示。项目持续完善中，仅用于学习和演示，不连接真实券商或支付渠道。

## 主要功能

- **交易与账户**：分证券持仓和订单簿、限价买卖、价格优先／时间优先撮合、部分成交、撤单解冻、历史委托和本人成交明细。
- **模拟入金**：申请状态查询、原申请重试和幂等处理，不涉及真实资金，也不赠送股票。
- **行情与恢复**：盘口、最近成交、多周期 K 线、WebSocket 更新；引擎通过快照和增量事件重放恢复。
- **监控与只读助手**：可选 Prometheus／Grafana；助手支持本人账户、订单、入金和公开行情查询，以及带出处的项目知识问答。助手不能代替用户执行交易，也不提供价格预测。

## 快速启动

以下命令适用于 Windows。请准备 **JDK 17、Maven、PowerShell 5.1 或 7、Docker Desktop（Linux 容器）**，并确认 `java`、`mvn` 和 `docker compose` 可用。使用脚本不需要 IDEA。首次构建需要网络拉取 Maven 依赖和容器镜像。

### 1. 获取代码并配置一次

```powershell
git clone -b feat/securities-exchange https://github.com/yexiaodesuki/Stock-Exchange.git
cd Stock-Exchange
Copy-Item .\build\.env.securities.example .\build\.env.securities
```

只在首次配置时复制模板；已有 `.env.securities` 时不要覆盖。编辑 `build/.env.securities`，将 `replace-with-` 占位值换成本机生成的随机值：

- `SEC_MYSQL_ROOT_PASSWORD`、`SEC_MYSQL_PASSWORD`：MySQL 管理及应用密码。
- `SECURITIES_AUTH_KEY`：至少 32 字符的签名密钥。
- `SEC_GRAFANA_PASSWORD`：Grafana 管理密码，保留有效配置以供同一 Compose 文件使用。
- 使用模板默认的演示模式时，填写 `SECURITIES_DEMO_USER1_PASSWORD` 至 `SECURITIES_DEMO_USER10_PASSWORD`；不需要演示账户可设置 `SECURITIES_DEMO_ENABLED=false`。

真实配置被 Git 忽略，不要提交或分享。交易启动不需要模型路径或大模型 API 密钥。

### 2. 启动并访问页面

开启 Docker Desktop 后，在仓库根目录运行：

```powershell
.\start.ps1
```

默认启动 **MySQL、Redis、Kafka、Nginx** 四个容器。脚本按需打包，在宿主机先启动 Config Server，再启动业务应用并检查网关。首次运行会初始化数据库与 Kafka Topic；不必另跑建表 SQL 或先手动执行 Compose。

打开 [http://localhost:8080](http://localhost:8080)。若 PowerShell 执行策略阻止脚本，可使用 `powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1`，不必改系统策略。

### 3. 体验交易

- **普通账户**：注册后初始余额为零。登录后打开“模拟入金 / 入金记录”，等状态变为“已到账”，再选择证券，填写限价和整数股数提交买入委托。是否成交取决于是否存在匹配的对手单。
- **演示账户**：全新环境启用演示模式时，初始化 `user1@example.com` 至 `user10@example.com`，密码来自本地配置。可以用两个独立浏览器会话分别挂卖单、买单，观察撮合后资金、持仓及成交明细的变化。
- 提交后看“请求进度”和“当前委托”；未成交部分可撤销。超时不等于失败，先核对原请求，不要重复下单或入金。

## 按需启动监控和 RAG

| 命令 | 在核心四个容器之外启动 |
| --- | --- |
| `.\start.ps1 -Monitoring` | Prometheus、Grafana |
| `.\start.ps1 -Rag` | Chroma、Embedding |
| `.\start.ps1 -Full` | 两组全部开启，共八个容器 |

监控和 RAG 不参与交易就绪判定。脚本不会自动停止已经运行的附加容器。仅运行 Compose 只会启动容器，不会启动宿主机上的 Java 应用；使用 IDEA 或手动 Compose 的步骤见 [运行说明](docs/running.md)。

- **监控**：[Grafana](http://localhost:3000) 默认用户为 `admin`，首次初始化密码取本地配置；[Prometheus Targets](http://localhost:9090/targets) 可检查抓取状态。复用已有 Grafana 数据卷时，修改环境变量不会重置密码。
- **本地 Embedding**：RAG 使用固定版本的 `BAAI/bge-small-zh-v1.5`，无需外部嵌入 API。保留 `SEC_RAG_MODEL_DOWNLOAD=true` 可首次联网下载；若已有相同版本，可把 `SEC_RAG_MODEL_SOURCE` 指向自己的 Hugging Face `hub` 缓存根目录。Java API 会自动将项目文档建索引，无需手动导入 Chroma。
- **外部大模型**：默认关闭。不填密钥也可返回检索原文和出处。要启用外部生成，在 `build/.env.securities` 设置 `SEC_ASSISTANT_EXTERNAL_ENABLED=true`，并填写 `SEC_ASSISTANT_ENDPOINT`（完整的 HTTPS 聊天接口地址）、`SEC_ASSISTANT_MODEL` 和 `SEC_ASSISTANT_API_KEY`。知识生成默认跟随外部模型开关，也可用 `SEC_RAG_GENERATION_ENABLED=false` 单独关闭。供应商接口、模型权限和额度需要自行验证；修改配置后重启 API。

设为 `SEC_RAG_ENABLED=false` 时，`-Rag` 或 `-Full` 也会跳过 RAG。知识回答会明确区分生成、检索原文和降级。外部分类会发送问题；知识生成还会附上公开文档片段，不附带账户数据。请勿在问题中输入密钥、密码或其他隐私。详见 [助手说明](docs/assistant.md)。

## 系统架构

```text
浏览器 → Nginx → UI / 交易 API → Kafka → 定序 → 撮合与清算
         └────→ WebSocket Push ←──────────── 行情与业务输出
```

| 模块 | 职责 |
| --- | --- |
| `config`、`config-repo` | Spring Cloud Config 服务及公共配置；私密配置保留在本机 |
| `trading-api`、`trading-sequencer` | 接收与查询交易请求、分配事件顺序 |
| `trading-engine` | 资金、持仓、订单、撮合、清算及恢复 |
| `quotation`、`push`、`ui` | 成交与 K 线查询、WebSocket 推送、页面和会话 |
| `common`、`parent`、`build` | 共享协议、依赖管理、Maven 聚合、Compose、SQL 与测试脚本 |

MySQL 保存业务数据、持久事件和快照；Kafka 承载事件流转；Redis 用于查询缓存和推送分发，不作为资产恢复的权威来源。监控与 RAG 独立于撮合清算。更多代码入口见 [业务分层](docs/business-layering.md) 和 [目录约定](docs/package-layout.md)。

## 停止、验证与进一步阅读

```powershell
.\stop.ps1
mvn -f build/pom.xml test
```

`stop.ps1` 只停止脚本管理的 Java 应用，不停止 Docker。需要暂停全部容器时，可从根目录执行 `docker compose --env-file build/.env.securities -f build/docker-compose.yml --profile monitoring --profile rag stop`。不要把删除数据卷当作普通重启。若需要强制重新打包，先停止旧 Java 应用，再运行 `.\start.ps1 -Rebuild`，并按需加启动选项。

- [运行与联调](docs/running.md)：IDEA 启动、端口、容器和隔离验证步骤。
- [验收记录](docs/verification.md)：交易、恢复和集成验证范围；普通 Maven 测试不等于完整联调。
- [网关](docs/nginx.md) · [模拟入金](docs/deposits.md) · [演示账户](docs/demo-users.md) · [RAG 实现](docs/rag-implementation.md)。

当前配置面向本地开发和演示，默认使用本地 HTTP，不是公网部署方案，也不提供生产级资金安全、高可用或模型回答正确性保证。
