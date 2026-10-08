<!-- 项目说明：完整初始化一次建表，日常启动保留数据并自动恢复引擎。 -->
# Stock Exchange

一个基于 Java 和 Spring Cloud 的交易系统雏形，包含资产管理、订单处理、撮合交易、清算、行情与实时推送等基础功能。项目处于初步开发阶段，后续将持续更新与优化。

## 技术栈

- Java 17、Spring Boot、Spring Cloud Config
- Maven 多模块工程
- MySQL、Redis、Kafka
- Vert.x、WebSocket
- Vue、Bootstrap

## 项目结构

| 模块 | 功能 |
| --- | --- |
| common | 公共模型、工具与基础组件 |
| config | 配置服务 |
| config-repo | 各应用的配置文件 |
| trading-api | 交易 API |
| trading-sequencer | 交易事件定序 |
| trading-engine | 资产管理、订单、撮合与清算 |
| quotation | 成交行情与 K线 |
| push | WebSocket 实时推送 |
| ui | 用户界面 |
| build | Maven 聚合构建、数据库脚本与基础设施配置 |
| parent | 公共依赖与构建配置 |

## 本地运行

1. 准备 Java 17、Maven 和 Docker。
2. 在项目的 `build` 目录执行 `docker compose -p stock-exchange up -d`，用 `docker compose -p stock-exchange ps` 确认 MySQL、Redis、Kafka 均健康。已有同名容器时先核对来源，不要直接覆盖。
3. 在 IDEA 中导入 `build/pom.xml`。
4. 检查 `config-repo` 中的连接配置。
5. 依次启动配置服务、定序服务、交易引擎、交易 API、行情服务、推送服务和 UI。
6. 访问 `http://localhost:8000`。

请勿将真实密码、访问令牌或 AI API Key 提交到仓库。

### 首次安装与日常重启

MySQL 使用空的 `build/docker/mysql-data` 目录时，会自动执行 `build/sql/schema.sql`，一次建立全部业务表和 `engine_snapshots` 快照表。不需要中途补建快照表，也不需要手工插入快照。

交易引擎每次启动都自动执行恢复协调器：空库校验初始状态并保存零序号快照；已有数据时加载有效快照并重放后续事件。只有校验和启动快照保存成功后才开放业务，并启动周期快照任务。访问 `http://localhost:8002/internal/status`，应返回 200 且 `ready` 为 `true`。

日常重启保留三个组件的数据目录，直接启动容器和应用，禁止重新执行 `schema.sql`（包含删库语句）。恢复、周期保存和验证步骤详见 [快照说明](docs/engine-snapshots.md)。

刻意从零重建开发环境时，先停止所有业务应用，核对容器实际挂载，停止并移除本项目容器，再备份或移走三个组件的数据目录。只删除容器不会清除宿主机目录；不要只清空 MySQL 而保留旧 Kafka 消息及 Redis 缓存。旧教程目录创建的容器不等于当前项目环境。

空库没有用户或测试资金：需重新注册并通过业务流程准备资金。初始快照不会虚构余额。验证挂单、成交、撤单后，记录余额和活动订单，保留数据重启应用，再检查恢复结果和行情、WebSocket。

## 后续计划

- 引入 Nginx 网关与统一访问入口
- 完善系统监控、日志与告警
- 优化可靠性、性能与安全性
- 补充自动化测试与部署流程
- 探索 AI 辅助分析与智能交互功能
- 持续改善界面与使用体验

## 项目状态

当前版本为系统雏形，主要用于开发、学习与验证，不适用于真实资金交易。
