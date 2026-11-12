<!-- 项目首页：介绍模拟证券交易、首次启动、可选监控与知识问答。 -->
# Stock Exchange

一个使用 Java 17、Spring Boot 和 Spring Cloud Config 构建的模拟证券交易系统。五只虚构股票共用一个 USD 资金账户，支持从注册、模拟入金、限价委托到撮合、清算和行情推送的完整演示。项目持续完善中，仅用于学习和演示，不连接真实券商或支付渠道。

## 主要功能

- **交易与账户**：分证券持仓和订单簿、限价买卖、价格优先／时间优先撮合、部分成交、撤单解冻、历史委托和本人成交明细。
- **模拟入金**：申请状态查询、原申请重试和幂等处理，不涉及真实资金，也不赠送股票。
- **行情与恢复**：盘口、最近成交、多周期 K 线、WebSocket 更新；引擎通过快照和增量事件重放恢复。
- **监控与只读助手**：可选 Prometheus／Grafana；助手支持本人账户、订单、入金和公开行情查询，以及带出处的项目知识问答。助手不能代替用户执行交易，也不提供价格预测。

## 快速启动

以下步骤面向 **Windows 本地开发与演示**。只跑交易不需要 IDEA、Node.js、Python、AI 密钥或模型文件，也不用在 Windows 上单独安装 MySQL、Redis、Kafka。首次启动需要联网下载 Maven 依赖和 Docker 镜像。

### 0. 安装并检查工具

1. **Git**：安装 [Git for Windows](https://git-scm.com/install/windows)，安装时让 Git 可在命令行中使用。
2. **JDK 17**：安装 [Eclipse Temurin JDK](https://adoptium.net/installation/windows/)，选择 **17、JDK**，不是 JRE。安装时启用添加到 `Path` 和设置 `JAVA_HOME`。如果手动设置，`JAVA_HOME` 指向 JDK 安装目录，`Path` 加入 `%JAVA_HOME%\bin`。
3. **Maven**：按 [Apache Maven 安装说明](https://maven.apache.org/install.html) 下载 3.9.x 的 **Binary zip archive**，解压后把 Maven 的 `bin` 目录加入 Windows 用户环境变量 `Path`。使用 Windows 开始菜单搜索“编辑账户的环境变量”即可修改。
4. **Docker Desktop**：按 [Windows 安装说明](https://docs.docker.com/desktop/setup/install/windows-install/) 准备 WSL 2 和虚拟化，安装后打开 Docker Desktop，等待引擎就绪。项目需要 **Linux 容器**。

重新打开 PowerShell（Windows PowerShell 5.1 或 PowerShell 7），逐条检查：

```powershell
git --version
java -version
mvn -version
docker compose version
docker info --format '{{.OSType}}'
```

`java -version` 和 `mvn -version` 都应显示 Java 17，最后一条应返回 `linux`。如果命令找不到，先检查 `Path`，再新开终端；如果 Maven 用的是另一套 Java，检查 `JAVA_HOME`。如果 Docker 命令存在但连不上引擎，先打开 Docker Desktop 并等待就绪。

### 1. 获取代码并配置一次

```powershell
git clone --branch main https://github.com/yexiaodesuki/Stock-Exchange.git
cd Stock-Exchange
Copy-Item .\build\.env.securities.example .\build\.env.securities
notepad .\build\.env.securities
```

只在首次配置时复制模板；已有 `.env.securities` 时不要覆盖。保留 `KEY=value` 格式（等号两侧不加空格，值不加引号），只改等号右边。把下面必填项的 `replace-with-` 占位值换成不同的随机值；初次可用密码管理器生成只含大小写字母和数字的随机串，避免配置文件转义问题：

- `SEC_MYSQL_ROOT_PASSWORD`、`SEC_MYSQL_PASSWORD`：MySQL 管理及应用密码。
- `SECURITIES_AUTH_KEY`：至少 32 字符的签名密钥。
- `SEC_GRAFANA_PASSWORD`：Grafana 管理密码，保留有效配置以供同一 Compose 文件使用。
- 使用模板默认的演示模式时，填写 `SECURITIES_DEMO_USER1_PASSWORD` 至 `SECURITIES_DEMO_USER10_PASSWORD`；不需要演示账户可设置 `SECURITIES_DEMO_ENABLED=false`。

想先验证两人撮合，建议保留演示模式。端口、数据库名和 AI 设置首次保持模板默认即可。真实配置被 Git 忽略，不要提交或分享。复用已有数据库时，不要用改配置文件的方式重置已有数据库或演示账户密码。

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

**最小成交验收**：在两个独立会话分别登录 `user1@example.com` 和 `user2@example.com`（例如普通窗口和无痕窗口），选择同一只股票，确认盘口没有会先吃掉测试委托的对手单。让一个有持仓的账户挂出 1 股限价卖单，另一个有足够 USD 的账户用同价买入 1 股。检查两边的持仓、资金和成交记录，以及该证券的最近成交。没有匹配订单时，委托留在盘口是正常的。

### 4. 停止与验证

```powershell
.\stop.ps1
mvn -f build/pom.xml test
```

`stop.ps1` 只停止脚本管理的 Java 应用，不停止 Docker。需要暂停全部容器时，可从根目录执行 `docker compose --env-file build/.env.securities -f build/docker-compose.yml --profile monitoring --profile rag stop`。不要把删除数据卷当作普通重启。若需要强制重新打包，先停止旧 Java 应用，再运行 `.\start.ps1 -Rebuild`，并按需加启动选项。

### 启动失败先查哪里

- **提示缺配置**：在项目根目录确认 `Test-Path .\build\.env.securities` 返回 `True`，并检查必填项没有保留占位值。不要把真实配置贴到 Issue。
- **镜像拉取或端口报错**：确认 Docker 引擎可用，从根目录运行 `docker compose --env-file build/.env.securities -f build/docker-compose.yml ps -a` 查看容器。默认网关占用 `8080`，Config Server 占用 `8888`；有冲突先确认占用者，不要盲目关闭其他服务或删除数据卷。
- **打包失败、应用没起来或网关返回 502**：查看脚本打印的 `build/.local-run/` 下本轮日志。打包先看 `build.log`；应用先看对应的 `*.out.log`、`*.err.log`，从 Config Server 开始定位，不要只看浏览器报错。

## 按需启动监控和 RAG

| 命令 | 在核心四个容器之外启动 |
| --- | --- |
| `.\start.ps1 -Monitoring` | Prometheus、Grafana |
| `.\start.ps1 -Rag` | Chroma；本地嵌入模式另启 Embedding |
| `.\start.ps1 -Full` | 监控与 RAG；本地嵌入共八个容器，外部嵌入共七个 |

监控和 RAG 不参与交易就绪判定。脚本不会自动停止已经运行的附加容器。仅运行 Compose 只会启动容器，不会启动宿主机上的 Java 应用；使用 IDEA 或手动 Compose 的步骤见 [运行说明](docs/running.md)。

- **监控**：[Grafana](http://localhost:3000) 默认用户为 `admin`，首次初始化密码取本地配置；[Prometheus Targets](http://localhost:9090/targets) 可检查抓取状态。复用已有 Grafana 数据卷时，修改环境变量不会重置密码。

### Embedding 模型配置

Embedding 用于知识检索，把文档和问题转成向量。以下配置填在 `build/.env.securities`，**本地和外部二选一**，与聊天模型的选择无关。已有配置文件不必重新复制。

#### 本地 Embedding（默认）

```dotenv
SEC_RAG_ENABLED=true
SEC_RAG_EMBEDDING_PROVIDER=local
SEC_RAG_MODEL_DOWNLOAD=true
# 可选：复用已有的 Hugging Face hub 缓存根目录
# SEC_RAG_MODEL_SOURCE=D:/your-cache/hub
```

本地模式固定使用项目指定版本的 `BAAI/bge-small-zh-v1.5`，不需要填模型名、API 地址或密钥。首次可联网下载；已有同版本权重时，可取消注释并填写缓存根目录。如果要禁止联网下载，将 `SEC_RAG_MODEL_DOWNLOAD` 设为 `false`，并确保指定缓存或现有模型卷已有正确版本。

#### 外部 Embedding

用供应商提供的实际地址、模型名和密钥替换示例值：

```dotenv
SEC_RAG_ENABLED=true
SEC_RAG_EMBEDDING_PROVIDER=external
SEC_RAG_EMBEDDING_ENDPOINT=https://provider.example/v1/embeddings
SEC_RAG_EMBEDDING_MODEL=your-embedding-model
SEC_RAG_EMBEDDING_API_KEY=your-embedding-api-key
SEC_RAG_EMBEDDING_REVISION=1
```

供应商须支持 OpenAI 兼容的 Embeddings JSON 协议：请求包含 `model` 和字符串数组 `input`，返回 `data[].index` 与数字数组 `data[].embedding`，并支持 Bearer 鉴权。端点填完整 HTTPS 地址，不是聊天地址或仅填 `/v1`。外部服务会收到公开知识文档片段和用户的检索问题，可能计费。

供应商若在同一模型名下更新权重或改变输出维度，请递增 `SEC_RAG_EMBEDDING_REVISION` 后重启 API。

保存后，若项目已运行，先执行 `.\stop.ps1`，再执行 `.\start.ps1 -Rag`。本地模式准备 Chroma 和 Embedding；外部模式只准备 Chroma，不会自动停止此前已运行的 Embedding 容器。API 会自动建索引，切换模式不用手动导入文档或删除 Chroma 数据。索引未就绪或调用失败时，知识问答退回原文检索，不影响交易。设置 `SEC_RAG_ENABLED=false` 后，即使指定 `-Rag` 或 `-Full` 也会跳过 RAG。

### 聊天模型配置

聊天模型用于问题分类和基于证据生成知识答案，默认不调用模型。在同一个 `build/.env.securities` 中选择以下一种方式；本地聊天可以搭配外部 Embedding，反过来也可以。

#### 本地聊天模型

先自行启动支持 Chat Completions 的本地推理服务（例如 Ollama），并按服务说明下载模型。示例配置：

```dotenv
SEC_ASSISTANT_PROVIDER=local
SEC_ASSISTANT_ENDPOINT=http://127.0.0.1:11434/v1/chat/completions
SEC_ASSISTANT_MODEL=your-installed-model
SEC_ASSISTANT_API_KEY=
```

填写本机实际安装的模型名，保留完整接口路径。只允许 `localhost`、`127.0.0.1` 或 `::1`，密钥可留空；此模式不需要开启 `SEC_ASSISTANT_EXTERNAL_ENABLED`。项目启动脚本不会代为启动本地推理服务。

#### 外部聊天模型

```dotenv
SEC_ASSISTANT_PROVIDER=external
SEC_ASSISTANT_EXTERNAL_ENABLED=true
SEC_ASSISTANT_ENDPOINT=https://provider.example/v1/chat/completions
SEC_ASSISTANT_MODEL=your-chat-model
SEC_ASSISTANT_API_KEY=your-chat-api-key
```

填供应商的完整 HTTPS Chat Completions 端点、实际模型名和聊天 API 密钥。选 `external` 并不等于启用调用，必须同时打开 `SEC_ASSISTANT_EXTERNAL_ENABLED`。外部分类会发送用户问题；知识生成还会发送公开文档片段，不附带账户数据。请勿在问题中输入密钥、密码或其他隐私。

改完后重启 API。问题分类不依赖 RAG；生成带引用的知识答案需要 RAG 可用，且聊天服务支持 JSON 对象输出。设置 `SEC_RAG_GENERATION_ENABLED=false` 可单独关闭生成，保留分类与原文检索。更多细节见[助手说明](docs/assistant.md)。

## 技术栈

| 层次 | 技术与用途 |
| --- | --- |
| 后端与构建 | Java 17、Spring Boot、Spring Cloud Config、Maven 多模块 |
| 数据与事件 | MySQL 持久化、Flyway 迁移、Kafka 事件流转、Redis 查询缓存与推送分发 |
| 页面与实时通信 | HTML、CSS、原生 JavaScript 模块、Canvas K 线、Vert.x WebSocket |
| 网关与环境 | Nginx 统一入口、Docker Compose 管理依赖、PowerShell 管理宿主机 Java 应用 |
| 可选监控 | Spring Boot Actuator、Prometheus、Grafana |
| 可选知识问答 | Python、Sentence Transformers、BGE 中文嵌入、Chroma、兼容 Chat Completions 的外部生成接口 |

## 系统架构

```text
浏览器 → Nginx → UI / 交易 API → Kafka → 定序 → 撮合与清算
         └────→ WebSocket Push ←──────────── 行情与业务输出
```

MySQL 保存业务数据、持久事件和快照；Kafka 承载事件流转；Redis 用于查询缓存和推送分发，不作为资产恢复的权威来源。监控与 RAG 独立于撮合清算。更多代码入口见 [业务分层](docs/business-layering.md) 和 [目录约定](docs/package-layout.md)。

## 项目结构

```text
Stock-Exchange/
├─ build/               Maven 聚合入口、Compose、网关、监控、SQL 和测试脚本
│  └─ rag/embedding/    本地嵌入服务的镜像和源码
├─ parent/              公共依赖与构建配置
├─ common/              共享证券协议、持久化和缓存组件
├─ config/              Spring Cloud Config Server
├─ config-repo/         公共配置与各应用配置，不存放本机密钥
├─ trading-api/         交易与查询接口、模拟入金、只读助手
├─ trading-sequencer/   事件定序与持久化
├─ trading-engine/      资金、持仓、订单、撮合清算与快照恢复
├─ quotation/           成交与 K 线行情
├─ push/                WebSocket 实时推送
├─ ui/                  交易页面、登录会话与 API 代理
├─ docs/                运行、架构和验收文档
├─ start.ps1            按需打包和启动
└─ stop.ps1             停止脚本管理的 Java 应用
```

## 进一步阅读

- [运行与联调](docs/running.md)：IDEA 启动、端口、容器和隔离验证步骤。
- [验收记录](docs/verification.md)：交易、恢复和集成验证范围；普通 Maven 测试不等于完整联调。
- [网关](docs/nginx.md) · [模拟入金](docs/deposits.md) · [演示账户](docs/demo-users.md) · [RAG 实现](docs/rag-implementation.md)。

当前配置面向本地开发和演示，默认使用本地 HTTP，不是公网部署方案，也不提供生产级资金安全、高可用或模型回答正确性保证。
