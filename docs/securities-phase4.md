<!-- 第四阶段交付说明：证券 UI、身份代理、演示事件与测试；不把本地模拟描述成生产交易所。 -->
# 第四阶段：UI 与演示初始化

> 历史实现记录：本页的 dev 启动脚本和旧 Compose 已移除。当前启动及验证以 [running.md](running.md) 为准，勿执行本页旧环境命令。

## 运行边界

新证券 UI 是 `ui/src/main/java/com/itranswarp/securities/ui/SecuritiesUIApplication.java`，端口 8100，管理端口 18100。
只启动此入口，不同时启动旧 `com.itranswarp.exchange.UIApplication`。证券版六个业务进程不依赖旧 Config Server。
`ui.jar` 仍为旧 BTC 入口，`ui-securities.jar` 才是证券入口。旧实现保留用于迁移对照，不要混用数据库与 Cookie。

```text
浏览器（独立会话 Cookie + CSRF）
  → UI 8100（绑定当前用户，签短时 Bearer，限定代理路由）
  → API 8101 → Kafka sequence → 定序器 → events → 引擎
  → 持久执行结果/订单/清算 → 行情投影 → MySQL/Redis
  → HTTP 读取权威视图；Redis 提示 → Push 8106 → 浏览器重新读取
```

每个证券页共用一份前端逻辑，目录从后端加载：STAR 星河科技、GREEN 青山能源、CARE 安康医药、DAILY 优选消费、SAIL 远航工业，均使用 USD、整数股、0.01 价格步长。
前端没有依赖升级或构建框架：新增本地原生 ES 模块及 Canvas 蜡烛图，不加载旧 Vue/BTC 脚本，也不请求第三方 CDN。
这不是专业图表组件：目前显示最近 80 根 OHLC，没有技术指标、复权、交易日历或绘图工具。

## 启动

在根目录执行（不删除已有容器、数据卷或数据库）：

```powershell
.\dev.ps1 infra
mvn -f build/pom.xml package -DskipTests
```

`infra` 对新库依次执行 V1/V2/V3，对已有库只执行缺失的 V3；已应用版本不修改。
V3 只增加 `demo_steps`，不配资、不插入用户、不改历史行情。`.env.securities` 中仅补齐缺失的随机演示密码，原数据库密码与签名密钥不替换。

然后分别在六个终端启动：

```powershell
.\dev.ps1 engine
.\dev.ps1 sequencer
.\dev.ps1 quotation
.\dev.ps1 api
.\dev.ps1 push
.\dev.ps1 ui -Demo
```

最后一行只在需要首次演示或断点续跑时使用。已有 COMPLETE 记录再次带 `-Demo` 也不会重做演示。
日常可用 `.\dev.ps1 ui`，不会自动配资。访问 `http://localhost:8100`。
Linux/macOS 对应 `bash dev.sh ui --demo`，其他入口同名。第五阶段再整合一条命令管理所有业务进程及生命周期。

演示邮箱是 `buyer@example.com`、`seller@example.com`。
密码仅在被 Git 忽略的本地 `.env.securities`：`SECURITIES_DEMO_BUYER_PASSWORD`、`SECURITIES_DEMO_SELLER_PASSWORD`；用编辑器在本机查看，不提交、不截图、不发到聊天或日志。
这些配置不是已创建账户的密码重置接口；数据卷与对应配置文件必须共同保留，遗失旧密码不能靠生成新配置恢复登录。

普通新注册用户资金与持仓为零，没有自动赠送或开放充值接口。
演示初始化首次只允许未使用的证券业务库（允许目录、迁移、零序号快照存在）。如已有业务或同邮箱，不接管身份、不清空数据，而是拒绝演示。
不要为跑演示删除真实开发数据；需要另一个全新隔离环境时另行配置项目、端口、数据库与消息命名空间。

## 登录与代理代码

`UserService.create()` 在同一事务插入 users、user_profiles、password_auths；邮件唯一冲突全部回滚。
口令使用 Java 17 自带 `PBKDF2WithHmacSHA256`，600000 次、独立 16 字节盐与 256 位结果，字段写成 `pbkdf2$600000$...`。
验证采用常量时间比较，账号不存在也计算一次相同成本的哈希。此为避免引入额外库的本地方案，并非宣称优于 Argon2id；生产需单独评估密码算法、MFA 与分布式限流。

`MvcController.signin()` 核心调用顺序：

```java
rate(request);
var user=users.authenticate(body.email(),body.password());
request.changeSessionId();
request.getSession().setAttribute("user",user);
request.getSession().setAttribute("csrf",UUID.randomUUID().toString());
```

先验证密码，再轮换会话 ID 与防伪令牌。Cookie 名为 `SECURITIES_SESSION`，HttpOnly、SameSite=Strict；HTTPS 请求时 Servlet 容器采用 Secure Cookie，实际部署仍需验证代理/TLS配置。
会话在 UI 内存中，UI 重启后需重新登录，资产恢复不依赖会话。30 分钟超时指没有 HTTP 访问的空闲时间，页面轮询会续期；不等于用户不操作 30 分钟强制退出。
所有写请求（包含登录、注册、退出）在 Filter 中验证 `X-CSRF-Token`，并限制正文 4KiB。
公开 `/session` 只在同源会话下返回自身 CSRF 与显示资料；不返回后端签名密钥或 Bearer。

代理 `proxy()` 使用服务端会话 userId 签发后端令牌，不转发浏览器传来的 Authorization。
路径白名单不包含内部资金/管理接口；HTTP 不跟随上游重定向。

```java
if(user!=null)builder.header("Authorization","Bearer "+tokens.issue(
    Long.parseLong(user.id()),System.currentTimeMillis()+600000));
// POST 分支校验并转发 Idempotency-Key 后，保留实际响应状态：
var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
return ResponseEntity.status(response.statusCode())
    .contentType(MediaType.APPLICATION_JSON).body(response.body());
```

202 仍然是提交，409 仍然是冲突，503 仍然是不可用；不把网络超时说成订单已经失败。
`PlainDecimalSerializer.serialize()` 使用 `toPlainString()` 输出 BigDecimal，修复历史完全成交订单的 `0E-18` 剩余股数。只是表示变化，不改变数值、数据库精度或协议版本。
页面用 textContent/DOM 构建显示，不把姓名当 HTML 注入；响应设 no-store、禁止嵌入、仅加载本地脚本。
本机 IP 限流是有界单进程实现，不等于生产的多节点防攻击体系。

## 前端状态与交易

`domain.mjs`：价格先转 BigInt 整数分，数量为 BigInt 整股，金额预览为二者相乘；最终提交仍是十进制文本。
价格范围与后端 `SecurityDefinition` 一致。仅图形像素和时间格式使用 Number，不参与记账或订单金额生成。

`Selection.select()` 每切股票或周期递增 version；`refresh()` 捕获票据与 AbortController：

```javascript
const value=(await api(path,{signal})).value;
if(!selection.current(ticket)||closed)return;
```

旧响应即使晚到也不能覆盖新股票。资金请求也采用同一页面代次，失败明确显示“暂不可用”而非零余额。
账户总额是可用与冻结相加，不虚构成本、盈亏或涨跌幅。
行情、盘口、活动订单分别读取各自的真实视图，不宣称整页所有接口来自同一数据库事务。

WS 不直接追加数据：订阅确认、私人通知、市场提示与 resync 都让页面重新读取 HTTP，替换对应快照。
因此无需把乱序旧通知与 HTTP 数据拼接成第二份成交；5 秒轮询兜底漏提示。切股先取消原订阅再订阅新证券，私人通知始终按用户接收。
WS 一次性票据不写入存储，不打印连接 URL；10 分钟后端身份到期会重连取得新票据。
退出关闭当前页面连接，但没有跨设备令牌即时撤销机制，旧连接最多存活至已签发身份到期。

异步下单在发送前，把命令、固定幂等键与后续 requestId 存入按 userId 隔离的 sessionStorage：

```javascript
const item={path,body,securityId:String(securityId),label,
            key:crypto.randomUUID(),requestId:null};
pending.push(item);persist();await resolvePending();
```

页面刷新或 HTTP 丢响应时仍用原键；查询得到 COMPLETED 后再区分 accepted 与业务拒绝。
404 可能是尚未进入定序，不能直接认定订单失败。入口明确 400 且未获得 requestId 才移出待确认列表。
注销清除当前用户浏览器私人暂存，不撤销已经进入交易链路的订单；重新登录可在活动/历史订单查看结果。

## 演示初始化及恢复

`DemoBootstrap.start()` 只在 `securities.demo.enabled=true` 启动后台线程，不是第七个持久应用。
`run()` 先取得数据库范围的 `ProcessLease("demo")`，避免两份 UI 同时配资。
竞争实例未取得锁时只改变自身内存状态，不能把另一个实例的 bootstrap_runs 标为 FAILED；测试持有真实会话锁验证这一点。
依赖未就绪或出现不属于 demo_steps 的其他业务事件时停止，不静默清库。

首次 `prepare()` 把两名用户、bootstrap_runs 与全部 52 个步骤同事务登记：

1. 两人各 100000 USD、每只 1000 股，共 12 个配给事件。
2. STAR/GREEN/CARE/DAILY/SAIL 基准价分别 100/50/80/30/60，每只按 P、P+0.50、P+1 成交三笔，每笔 5 股，共 30 个下单事件与 15 笔成交。
3. 每只再挂买方 10 股@P−2、卖方 10 股@P+3，共 10 个挂单事件，不交叉。

每步固定 `demo-v1:001` 至 `demo-v1:052`，保存完整提交 JSON、用户、证券、哈希与完成序号。
配给送 transfer Topic、订单送 sequence Topic，均由现有定序器持久化，并经过真实引擎冻结、撮合与清算。
没有直接插入余额、ticks 或 bars；时间来自真实执行，三笔快速成交可能只有一根分钟 K线，这是正确行为。

`run()` 的关键确认边界：

```java
var result=outputs.result(submission.userId(),submission.uniqueId());
if(result.isPresent() && ready().path("applied").asLong()
        >= result.get().sequenceId()) {
    validate(submission,result.get());
    jdbc.update("UPDATE demo_steps SET sequenceId=?,status='COMPLETE',updatedAt=? WHERE stepId=?",
        result.get().sequenceId(),System.currentTimeMillis(),submission.uniqueId());
    done=true;break;
}
```

持久输出存在还不够，必须等引擎安装候选状态后才能推进步骤；再校验接受状态、预期成交数量与双方身份。
Kafka 发送超时重复发送同一幂等命令，事件日志不重复执行。
引擎完成但步骤尚未标记时崩溃，下次从已有输出确认，不再次发放资产；步骤完成且重启则验证旧结果后跳过。
bootstrap_runs.COMPLETE 是一次性终态：以后发生人工交易不回滚，余额不恢复成模板，演示成交不重复生成。
RUNNING/FAILED 会阻止此 UI 的注册与交易写请求，但不替代所有内部客户端的鉴权边界；外部绕过该 UI 的提交会让初始化检测到干扰并失败。
跨服务无法回滚已执行的资金事件，失败时保留现场供续跑/诊断，不通过撤销他人订单“修复”。

首次完成且未人工交易时预期：

|账户|USD 可用|USD 冻结|每证券可用|每证券冻结|
|---|---:|---:|---:|---:|
|买方|92062.50|3100.00|1015|0|
|卖方|104837.50|0.00|975|10|

买方现金总额 95162.50，卖方每证券总持仓 985；差额来自 15 笔共 4837.50 USD 的真实模拟成交。
页面成交由行情服务异步生成，演示 COMPLETE 后可能短暂“行情追赶中”，应等待投影就绪，不造空数组伪装成功。

## 测试与验收

本次实际验收（2026-10-27）：全仓库 Maven BUILD SUCCESS，186 条报告记录，184 通过、0 失败、0 错误；两个按环境开关禁用的旧监控直连/开发库测试组跳过。页面纯逻辑 4 项通过，六服务联调及真实 Edge 浏览器验收均实际执行。
初始化中途强杀 UI 后续跑成功；真实锁竞争不改写其他初始化器状态；六应用重启后资金、冻结和成交保持。
浏览器验证了历史明细、十五笔首批成交和后续人工成交、撤单、刷新幂等及网络恢复；最终完整轮询周期无持续 503、无 JavaScript 运行时异常。交易过程中会有明确的短暂行情追赶 503，不宣称从未出现非 200 响应。
本机已有证券环境已执行 V3、补齐本地随机演示密码，核对开发库 users/events/demo_steps 仍为 0；没有执行开发库配资。隔离测试库、临时 Topic/消费组、测试 Redis 键与测试应用均已清理。
详细构建日志在本机 `build/securities-phase4-final.log`，环境升级日志为 `build/securities-phase4-infra.log`（日志被 Git 忽略）。

```powershell
npm --prefix ui ci
.\build\securities\test-phase4.ps1
```

Windows 默认使用已安装 Edge；其他平台先安装测试浏览器：`npx --prefix ui playwright install chromium`，或通过 `SECURITIES_BROWSER_EXECUTABLE` 指定兼容的浏览器文件。
package.json/package-lock.json 只用于测试，生产运行无需 npm 或第三方 CDN。node_modules 必须被忽略。

- `domain.test.mjs`：十进制、超安全整数金额、不合法输入与切股代次。
- `MvcControllerTest`：代理路由、CSRF、CSP、正文上限，无外部依赖。
- `SecuritiesSerializationTest`：历史零值、正常价格与大值均按普通十进制字符串输出，不隐式舍入。
- `SecuritiesUiProcessTest`：自动迁移的随机库与随机 Kafka/Redis 命名空间，启动六个真正 JAR；演示中途强杀 UI 后续跑；断言 52 个事件/结果/步骤、15 笔行情、10 张挂单、盐与慢哈希、身份权限、原状态码、幂等、六应用重启后资产保持、普通注册不配资。
- `browser.mjs`：两个独立 Chromium 上下文，真实表单登录、五证券切换、K线与成交展示、慢响应切股、撤单、Maker 价格成交、HTTP 丢响应后刷新原键恢复、网络恢复与重新连接、桌面/移动截图与运行时错误检查。

测试只清理本轮精确命名的临时库、Topic、消费组、Redis 键和 JVM，不给开发库充值。
强杀用的是测试创建的 UI 进程；底层事件与资产恢复的其他强杀场景由第二/三阶段回归继续验证。
浏览器网络测试覆盖离线后恢复并刷新重连，不宣称穷尽真实物理断网、慢客户端或跨机故障。
完整无人工环境建立、自动进程管理和多轮保留数据启动验收仍属于第五阶段。
Windows 两种 PowerShell 的脚本解析及 BOM 由既有回归检查；Bash 在容器中做语法检查，未在本机完成 Linux 全流程运行。
局部排查可用 `test-phase4.ps1 -UiOnly`，它只运行本阶段新增类和浏览器，不等同于默认的全仓库回归。

## 回退与限制

回退 UI 代码不等于回退已执行的演示资金事件，不应删除事件、快照或手动改余额。
V3 为增量加表，原 V1/V2 内容不变，旧证券后端可继续使用同一兼容协议；不要删除 Flyway 历史。
新环境只监听本机，不具备生产 TLS、完整会话撤销、MFA、真实充值、交易时段或成本收益计算。
监控仪表盘仍以原应用为主，新证券应用的管理端口已存在，但旧监控面板未在此阶段重新设计。
