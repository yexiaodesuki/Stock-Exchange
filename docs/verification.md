<!-- 当前五容器与 IDEA 等价启动验收；只记录已运行结果，不包含私密值和原始业务数据。 -->
# 五容器与自动初始化验收

> 本页记录五容器改造时的验收。之后已删除旧 BTC 实现并恢复标准源码目录；最新布局、启动入口和清理回归见 [源码整理记录](source-cleanup.md)。

日期：2026-10-28。Windows、Java 17、Maven 3.9.16、PowerShell 7、Docker Desktop Linux 容器。

## 实际结果

| 验证 | 本轮结果 |
|---|---|
| Maven 隔离回归 | 79 条记录，78 通过，0 失败，0 错误，1 个直连开发库条件组跳过 |
| 前端纯逻辑 | 4 项通过 |
| 空库自动迁移 | 不运行 Flyway 容器，Java 入口执行 V1/V2/V3，目录及快照表存在 |
| 并发与重复迁移 | 三个并发目录加载均成功；证券、事件、快照和迁移历史指纹不变 |
| 核心与恢复 | 原有资产、撮合清算、快照、执行输出、恢复测试通过 |
| 真实跨进程联调 | Config Server、证券 API / 引擎 / 定序 / 行情 / Push / UI 进程测试通过 |
| 十用户初始化 | 隔离库内初始化、部分完成续跑、完整重启等 UI 进程断言通过 |
| Docker 最终状态 | mysql、redis、kafka、grafana、prometheus 五个容器均 healthy |
| 容器清理 | 移除本项目旧应用、任务、监控和旧 BTC 基础设施容器；未删除卷或旧绑定目录 |
| 开发数据保持 | 前后均为 2 个账户、54 个事件、16 笔 Tick、3 条成功迁移 |
| 私密文件读取 | 本地 Java 不使用启动脚本导出密码，直接读取 build/.env.securities 启动成功 |
| 安全监听 | 六个管理端口实测全部为 127.0.0.1，没有改成 0.0.0.0 |
| Prometheus | 六个证券 Java Target 同时 UP，promtool 校验通过 |
| Grafana | admin 密码经用户授权同步；数据源健康 OK；证券看板加载，六面板实际查询有返回且无错误 |
| 引擎就绪指标 | 通过 Grafana 数据源代理查询 exchange_securities_ready，实际值为 1 |
| Push | /actuator/health 返回 UP；不假称拥有 Prometheus 指标 |

本轮未重新执行浏览器视觉与交互全套验收；Grafana 已验证实际看板配置、数据源及查询数据，并非截图视觉验收。旧版全 Docker Java 镜像与 verify-stack 验收入口已移除，不再作为本轮启动要求。

## 关键实现依据

证券目录入口先迁移后读取：

```java
SchemaMigration.migrate(source);
StorageCompatibility.requireSecurities(source);
```

迁移只使用同一套版本 SQL，不创建第二套独立建表方案：

```java
Flyway.configure().dataSource(source).locations("classpath:db/securities")
        .cleanDisabled(true).baselineOnMigrate(false).validateMigrationNaming(true)
        .load().migrate();
```

V1/V2/V3 原文件未改写；它们由 common/pom.xml 从 build/sql/securities 打包进 JAR。依赖管理固定 Flyway 10.20.1，与原迁移历史兼容。多个应用并发加载由 Flyway 的数据库锁处理；迁移失败不进入目录和业务装配。

本地应用导入：

```yaml
spring:
  config:
    import:
      - optional:file:./build/.env.securities[.properties]
      - configserver:${CONFIG_SERVER:http://127.0.0.1:8888}
```

这将私密参数留在本机，配置服务仍只分发非敏感配置；原有 RequiredConfigEnvironment 配置版本守卫保留。

## 复现和当前状态

build 目录：

```powershell
docker compose --env-file .env.securities up -d --wait
```

IDEA 导入 build/pom.xml，在项目根目录工作路径依次启动配置服务和六个证券业务入口。完整流程见 [running.md](running.md)。

隔离回归在项目根目录使用：

```powershell
pwsh -File build/securities/test-phase2.ps1
node --test ui/src/test/js/domain.test.mjs
```

本轮自动测试创建的随机数据库已删除。验证监控时自行创建的七个本地 Java 进程已停止，释放默认端口；五个基础设施容器保持运行。因此未在 IDEA 启动应用时 Targets 重新 DOWN 是正确的，并不表示监控失效。

未动无关项目的 smartoperations-dev 和 nginx 容器；未清空 Docker 全局缓存、旧绑定数据目录或任何业务卷。历史看板和监控数据继续保留，证券专用看板在 Securities 文件夹。

私密配置和构建产物被 Git 忽略；没有执行 Git 提交或推送。Linux/macOS 本机网络、生产 TLS/MFA、高可用、动态配置刷新仍未验收。
