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
2. 启动 MySQL、Redis、Kafka，并初始化数据库表结构。
3. 在 IDEA 中导入 `build/pom.xml`。
4. 检查 `config-repo` 中的连接配置。
5. 依次启动配置服务、定序服务、交易引擎、交易 API、行情服务、推送服务和 UI。
6. 访问 `http://localhost:8000`。

请勿将真实密码、访问令牌或 AI API Key 提交到仓库。

## 后续计划

- 引入 Nginx 网关与统一访问入口
- 完善系统监控、日志与告警
- 优化可靠性、性能与安全性
- 补充自动化测试与部署流程
- 探索 AI 辅助分析与智能交互功能
- 持续改善界面与使用体验

## 项目状态

当前版本为系统雏形，主要用于开发、学习与验证，不适用于真实资金交易。
