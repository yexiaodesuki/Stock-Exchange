<!-- Nginx 本地网关说明：保留浏览器后端身份边界、回环管理入口及独立 WebSocket 票据认证。 -->
# 统一网关

默认访问 http://localhost:8080。Compose 增加固定名称 nginx 的第六个容器，不把 Java 应用改为容器，也不修改数据库结构或清空数据。当前是本地 HTTP 开发部署，不是公网生产部署。

## 请求链

- 页面、Session、登录和 /api/v1/：浏览器 → Nginx → UI 8100 → 内部 API 8101。
- /ws：浏览器 → Nginx → Push 8106。仍需通过 UI 获取一次性票据，并通过 Push 的 Origin 检查。
- /internal、/actuator、未知路径：网关默认 404。未知 Host 默认 421。

不能将 /api/v1 直接代理到 API，否则会绕开 UI 的 Session 身份转换与 CSRF。没有缓存私人接口，也没有自动重试交易请求；Idempotency-Key 原样交给原业务流程。

配置位于 build/nginx/nginx.conf 和 conf.d/exchange.conf，通过只读挂载交给 Nginx。上游使用 host.docker.internal 访问宿主机固定 UI 8100、Push 8106。Java 和管理端口继续回环监听；Docker Desktop 的具体连通性由隔离网关测试验证，不承诺普通 Linux Engine 能按相同方式访问回环端口。自定义这两个 Java 端口须同步修改网关上游，启动器会拒绝不匹配的端口。

Docker Desktop 实测同时解析出宿主机 IPv4、IPv6，而 Java 仅绑定 IPv4 回环。静态 upstream 会轮流尝试不可达 IPv6 导致间歇 502；因此使用带 zone 的动态 upstream，明确交给 Docker DNS 127.0.0.11 且 ipv6=off 解析。不硬编码宿主机 IP、不通过开放 0.0.0.0 解决。测试容器接入同一个 Compose 用户网络以使用内置 DNS，但仍使用独立名称、随机端口和隔离数据库。

## 同源与可信代理

UI 的默认 websocket-url 为 /ws，前端根据当前页面生成 ws/wss、主机与端口；不会再默认直连 8106。CSP 允许固定同源路径，仍兼容显式配置的绝对 WebSocket 地址。Push 默认允许本地网关以及原 UI 调试 Origin。若直接用 8100 页面调试推送，需要显式配置指向 8106 的绝对 WebSocket 地址；正常完整交易请使用网关入口。

Nginx 覆盖 X-Forwarded-For、X-Forwarded-Host、协议头并清除 Forwarded，不追加浏览器提供的伪造地址。UI Tomcat 的 native 转发处理只信任 127.0.0.1 与 IPv6 回环；不启用任意私网代理信任。这样 SessionService 仍使用 getRemoteAddr 获取经可信代理处理后的地址。宿主机本地进程属于此信任边界；这不是针对本机恶意程序的隔离机制。

入口为登录/握手和普通请求分设限流，返回 429；业务认证、限流、CSRF、订单归属与幂等检查继续保留。请求正文限制 4 KiB，HTTP 上游有连接与读写超时；WebSocket 超时 75 秒，并保留原 Push 心跳。WebSocket location 显式重复全部可信头，避免 Nginx 子级 proxy_set_header 不继承父级造成缺失。

访问日志使用 $uri，不记录查询参数、Cookie、Authorization 或正文。/ws 的错误日志被抑制，防止 Nginx 在上游失败时将含 ticket 的原始请求行写入日志；仍可通过不含票据的访问状态和耗时排查握手失败。这是日志隐私与诊断细节的明确取舍。

## 启停与检查

start.ps1 补齐六个容器，仍先等待 Config 和六份配置，再依次等待 Java 应用就绪；最后经 Nginx 检查 /session 含 CSRF 和固定 /ws 配置，成功后打开网关。Nginx 容器的 /gateway-health 只证明自身存活，不代表 Java 就绪。

stop.ps1 仍只停止脚本记录的 Java，不停止 Nginx、Docker 或删除数据卷。停止 Java 后 /gateway-health 仍可能成功，而业务请求返回 502，这是如实报告上游不可用。

本机调试地址不变：UI 8100、API 8101、引擎 8102、配置 8888、相应管理端口，以及 Grafana 3000 和 Prometheus 9090；这些都不通过公共网关开放。修改 Nginx 挂载配置后先执行 docker exec nginx nginx -t，再执行 docker exec nginx nginx -s reload。仅重跑启动脚本不会热加载已运行的 Java 配置。

如果已有非本项目的 nginx 容器，Compose 会报同名冲突，脚本不自动删除或接管。须先确认旧容器用途，再明确授权迁移或重命名。Nginx 镜像固定为 nginx:1.28.3-alpine；更新版本应作为单独维护操作验证。

## 验收与边界

2026-10-29 已实际运行完整 `-Gateway` 回归：Java 共 97 项，96 项通过、1 项按环境条件跳过，失败和错误均为零；浏览器双身份交易、撤单、五证券行情、切股、刷新幂等、重连和桌面/移动布局通过。另通过 5 项前端纯逻辑测试、启动脚本 13 组测试及停止脚本 8 组测试。正式环境 user1、user2 经网关登录和只读账户查询返回 200，连续 20 次会话请求均返回 200；没有向开发账户提交测试交易。上述结果不等于公网安全评估或高并发压测。

运行 pwsh -File build/securities/test-phase4.ps1 -Gateway：使用随机测试库、消息前缀和应用端口，创建 UUID 名称的独立 Nginx。复用正式配置，仅替换两个上游端口，验证回环连通、拒绝内部路径、匿名账户 401、缺 CSRF 403，并让原双用户浏览器交易、撤单、行情和重连测试全程经过网关。结束仅删除自己的测试容器及测试资源，不删除开发数据。

不支持直接把当前配置公开到互联网。公网部署还需域名、TLS 证书、Secure Cookie、可信代理网络和主机防火墙策略；不能仅把网关绑定改为 0.0.0.0 就宣称生产安全。证书私钥不入库。回退可使用原本机调试端口，但在默认相对 /ws 下直接 UI 推送需上述显式地址配置。
