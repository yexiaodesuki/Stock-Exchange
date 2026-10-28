/* 六进程隔离验收：演示中断续跑、真实登录与代理、首批撮合、重复启动及浏览器页面操作。 */
package com.itranswarp.exchange.ui.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.itranswarp.exchange.securities.SecuritiesChannels;
import com.itranswarp.exchange.ui.service.UserService;
import io.lettuce.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "SECURITIES_PHASE2_DB_URL", matches = ".+")
class SecuritiesUiProcessTest {
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, Process> processes = new HashMap<>();
  private final Map<String, Integer> ports = new HashMap<>();
  private final HttpClient probe =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private String namespace, key, buyerPassword, sellerPassword, csrf;
  private Path root, logs;
  private JdbcTemplate jdbc;

  /** 已有账户时拒绝演示；重复邮箱事务回滚，失败的双身份准备不能留下半成品。 */
  @Test
  void refusesUsedEnvironmentAndRollsBackPartialIdentities() throws Exception {
    String url = System.getenv("SECURITIES_PHASE2_DB_URL");
    if (!url.matches("jdbc:mysql://[^/]+/exchange_securities_test_[a-f0-9]{32}\\?.+"))
      throw new IllegalStateException("禁止测试开发库");
    var source =
        new DriverManagerDataSource(url, "securities", System.getenv("SECURITIES_DB_PASSWORD"));
    jdbc = new JdbcTemplate(source);
    reset();
    var users = new UserService(source);
    var broken =
        new DemoBootstrap(
            source,
            users,
            "127.0.0.1:1",
            "securities.test.validation",
            "http://127.0.0.1:1",
            true,
            "valid-password-12345",
            "short");
    assertThrows(IllegalArgumentException.class, broken::prepare);
    assertEquals(0, count("SELECT COUNT(*) FROM users"));
    assertEquals(0, count("SELECT COUNT(*) FROM bootstrap_runs"));
    var original = users.create("owner@example.com", "已有用户", "valid-password-12345");
    assertEquals(original, users.authenticate("OWNER@example.com", "valid-password-12345"));
    assertThrows(
        IllegalArgumentException.class,
        () -> users.authenticate("owner@example.com", "wrong-password-12345"));
    assertThrows(
        UserService.EmailConflict.class,
        () -> users.create("owner@example.com", "重名用户", "valid-password-67890"));
    assertEquals(1, count("SELECT COUNT(*) FROM users"));
    assertEquals(1, count("SELECT COUNT(*) FROM password_auths"));
    var refused =
        new DemoBootstrap(
            source,
            users,
            "127.0.0.1:1",
            "securities.test.validation",
            "http://127.0.0.1:1",
            true,
            "valid-password-12345",
            "valid-password-67890");
    assertThrows(IllegalStateException.class, refused::prepare);
    assertEquals(0, count("SELECT COUNT(*) FROM demo_steps"));
    assertEquals(0, count("SELECT COUNT(*) FROM events"));
    reset();
    var competing =
        new DemoBootstrap(
            source,
            users,
            "127.0.0.1:1",
            "securities.test.validation",
            "http://127.0.0.1:1",
            true,
            "valid-password-12345",
            "valid-password-67890");
    competing.prepare();
    try (var held =
        new com.itranswarp.exchange.securities.persistence.ProcessLease(source, "demo")) {
      competing.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!competing.status().get("state").equals("FAILED") && System.nanoTime() < deadline)
        Thread.sleep(20);
      assertEquals("FAILED", competing.status().get("state"));
      assertEquals(1, count("SELECT COUNT(*) FROM bootstrap_runs WHERE status='RUNNING'"));
      assertEquals(0, count("SELECT COUNT(*) FROM events"));
    } finally {
      competing.close();
    }
  }

  /** 六个真实打包程序共用临时库；测试不得在开发库写入任何模拟资金。 */
  @Test
  void verifiesDemoRecoveryLoginProxyAndBrowser() throws Exception {
    String url = System.getenv("SECURITIES_PHASE2_DB_URL");
    if (!url.matches("jdbc:mysql://[^/]+/exchange_securities_test_[a-f0-9]{32}\\?.+"))
      throw new IllegalStateException("禁止测试开发库");
    var source =
        new DriverManagerDataSource(url, "securities", System.getenv("SECURITIES_DB_PASSWORD"));
    jdbc = new JdbcTemplate(source);
    reset();
    root = Path.of(System.getenv("SECURITIES_TEST_ROOT"));
    logs = Files.createTempDirectory("securities-phase4-");
    System.out.println("第四阶段六服务日志目录：" + logs);
    namespace = "securities.test." + UUID.randomUUID().toString().replace("-", "");
    key = UUID.randomUUID() + "-" + UUID.randomUUID();
    buyerPassword = UUID.randomUUID().toString();
    sellerPassword = UUID.randomUUID().toString();
    for (String module :
        List.of("trading-engine", "trading-sequencer", "quotation", "trading-api", "push", "ui"))
      ports.put(module, port());
    var channels = new SecuritiesChannels(namespace);
    var topics =
        List.of("sequence", "transfer", "trade", "tick").stream().map(channels::topic).toList();
    try (var admin =
            AdminClient.create(
                Map.of(
                    "bootstrap.servers",
                    System.getenv("SECURITIES_TEST_BOOTSTRAP"),
                    "request.timeout.ms",
                    5000,
                    "default.api.timeout.ms",
                    10000));
        var redis = RedisClient.create(System.getenv("SECURITIES_TEST_REDIS_URI"));
        var connection = redis.connect()) {
      admin
          .createTopics(topics.stream().map(t -> new NewTopic(t, 1, (short) 1)).toList())
          .all()
          .get(15, TimeUnit.SECONDS);
      try {
        start("trading-engine", false);
        await(() -> status("trading-engine", "/internal/securities/status") == 200, "引擎就绪");
        for (String module : List.of("trading-sequencer", "quotation", "trading-api", "push"))
          start(module, false);
        await(
            () ->
                status("trading-api", "/api/v1/securities") == 200
                    && status("push", "/actuator/health") == 200,
            "API 与推送启动");
        start("ui", true);
        await(
            () -> count("SELECT COUNT(*) FROM demo_steps WHERE status='COMPLETE'") >= 3, "至少三步已执行");
        // 真正强杀只由本测试拥有的 UI，不用优雅退出冒充突然中断。
        var killed = processes.remove("ui");
        killed.destroyForcibly();
        assertTrue(killed.waitFor(15, TimeUnit.SECONDS));
        int completed = count("SELECT COUNT(*) FROM demo_steps WHERE status='COMPLETE'");
        assertTrue(completed < 100, "必须在演示未结束时注入中断");
        start("ui", true);
        await(
            () -> count("SELECT COUNT(*) FROM bootstrap_runs WHERE status='COMPLETE'") == 1,
            "断点续跑完成");
        assertEquals(100, count("SELECT COUNT(*) FROM events"));
        assertEquals(100, count("SELECT COUNT(*) FROM engine_results"));
        assertEquals(100, count("SELECT COUNT(*) FROM demo_steps WHERE status='COMPLETE'"));
        await(() -> count("SELECT COUNT(*) FROM ticks") == 15, "五证券十五笔成交投影");
        await(
            () -> count("SELECT sequenceId FROM projection_offsets WHERE name='quotation'") == 100,
            "行情投影追平全部演示事件");
        assertEquals(10, count("SELECT COUNT(*) FROM users"));
        assertEquals(10, count("SELECT COUNT(*) FROM orders WHERE status='PENDING'"));
        assertEquals(
            10,
            count(
                "SELECT COUNT(*) FROM password_auths WHERE passwd LIKE 'pbkdf2$600000$%' AND"
                    + " LENGTH(random)=32"));
        assertEquals(10, count("SELECT COUNT(DISTINCT random) FROM password_auths"));
        var buyer = client();
        var anonymous = client();
        var staticModule = send(anonymous, "GET", "/securities/app.mjs", null, null, null);
        assertEquals(200, staticModule.statusCode());
        assertTrue(
            staticModule.headers().firstValue("Content-Type").orElse("").contains("javascript"),
            "浏览器模块 MIME 必须正确");
        assertEquals(401, send(anonymous, "GET", "/api/v1/account", null, null, null).statusCode());
        csrf = tree(send(buyer, "GET", "/session", null, null, null)).path("csrf").asText();
        assertEquals(403, send(buyer, "POST", "/auth/signin", "{}", null, null).statusCode());
        var login =
            send(
                buyer,
                "POST",
                "/auth/signin",
                json.writeValueAsString(
                    Map.of("email", "user1@example.com", "password", buyerPassword)),
                csrf,
                null);
        assertEquals(200, login.statusCode(), login.body());
        var identity = tree(send(buyer, "GET", "/session", null, null, null));
        assertEquals("用户一", identity.path("user").path("name").asText());
        String rotated = identity.path("csrf").asText();
        assertNotEquals(csrf, rotated);
        csrf = rotated;
        var account = tree(send(buyer, "GET", "/api/v1/account?userId=1", null, null, null));
        amount("92062.50", account.path("cash").path("available"));
        amount("3100", account.path("cash").path("frozen"));
        for (var position : account.path("positions")) amount("1015", position.path("available"));
        for (long security = 1001; security <= 1005; security++) {
          assertEquals(
              3,
              tree(send(
                      buyer, "GET", "/api/v1/securities/" + security + "/ticks", null, null, null))
                  .path("data")
                  .size());
          assertEquals(
              1,
              tree(send(buyer, "GET", "/api/v1/orders?securityId=" + security, null, null, null))
                  .size());
        }
        assertEquals(
            404,
            send(buyer, "POST", "/api/v1/internal/transfer", "{}", csrf, "unsafe").statusCode());
        assertEquals(
            400,
            send(
                    buyer,
                    "POST",
                    "/api/v1/orders",
                    "{\"securityId\":1001,\"direction\":\"BUY\",\"price\":\"1.001\",\"quantity\":\"1\"}",
                    csrf,
                    "invalid")
                .statusCode());
        String body =
            "{\"securityId\":1001,\"direction\":\"BUY\",\"price\":\"1.00\",\"quantity\":\"1\"}";
        var placed = send(buyer, "POST", "/api/v1/orders", body, csrf, "ui-own-request");
        assertEquals(202, placed.statusCode());
        String id = tree(placed).path("requestId").asText();
        await(
            () ->
                send(buyer, "GET", "/api/v1/requests/" + id, null, null, null).statusCode() == 200,
            "代理保留带冒号请求标识");
        assertEquals(
            409,
            send(
                    buyer,
                    "POST",
                    "/api/v1/orders",
                    body.replace("1.00", "2.00"),
                    csrf,
                    "ui-own-request")
                .statusCode());
        assertEquals(
            202, send(buyer, "POST", "/api/v1/orders", body, csrf, "ui-own-request").statusCode());
        await(() -> count("SELECT COUNT(*) FROM events") == 101, "幂等订单只执行一次");
        // 重启六服务恢复状态：COMPLETE 不会按初始模板再次配资或重做行情。
        for (String module : new ArrayList<>(processes.keySet())) stop(module);
        start("trading-engine", false);
        await(() -> status("trading-engine", "/internal/securities/status") == 200, "重启引擎恢复");
        for (String module : List.of("trading-sequencer", "quotation", "trading-api", "push"))
          start(module, false);
        start("ui", true);
        await(
            () ->
                status("ui", "/session") == 200 && status("trading-api", "/api/v1/account") == 401,
            "六服务重启");
        var again = client();
        String proof = tree(send(again, "GET", "/session", null, null, null)).path("csrf").asText();
        assertEquals(
            200,
            send(
                    again,
                    "POST",
                    "/auth/signin",
                    json.writeValueAsString(
                        Map.of("email", "user1@example.com", "password", buyerPassword)),
                    proof,
                    null)
                .statusCode());
        var restored = tree(send(again, "GET", "/api/v1/account", null, null, null));
        amount("92061.50", restored.path("cash").path("available"));
        amount("3101", restored.path("cash").path("frozen"));
        assertEquals(101, count("SELECT COUNT(*) FROM events"));
        assertEquals(15, count("SELECT COUNT(*) FROM ticks"));
        // 新注册用户身份存在但资金为零，不复用旧注册自动赠送机制。
        var newcomer = client();
        String fresh =
            tree(send(newcomer, "GET", "/session", null, null, null)).path("csrf").asText();
        assertEquals(
            200,
            send(
                    newcomer,
                    "POST",
                    "/auth/signup",
                    json.writeValueAsString(
                        Map.of(
                            "email",
                            "fresh@example.com",
                            "name",
                            "<script>alert(1)</script>",
                            "password",
                            UUID.randomUUID().toString())),
                    fresh,
                    null)
                .statusCode());
        amount(
            "0",
            tree(send(newcomer, "GET", "/api/v1/account", null, null, null))
                .path("cash")
                .path("available"));
        assertEquals(101, count("SELECT COUNT(*) FROM events"));
        var duplicate = client();
        String duplicateCsrf =
            tree(send(duplicate, "GET", "/session", null, null, null)).path("csrf").asText();
        assertEquals(
            409,
            send(
                    duplicate,
                    "POST",
                    "/auth/signup",
                    json.writeValueAsString(
                        Map.of(
                            "email",
                            "fresh@example.com",
                            "name",
                            "重复邮箱",
                            "password",
                            UUID.randomUUID().toString())),
                    duplicateCsrf,
                    null)
                .statusCode());
        assertEquals(11, count("SELECT COUNT(*) FROM users"));
        if ("true".equals(System.getenv("SECURITIES_TEST_BROWSER"))) browser();
        else System.out.println("未启用浏览器验收：设置 SECURITIES_TEST_BROWSER=true 后运行第四阶段脚本。");
        String logoutCsrf =
            tree(send(again, "GET", "/session", null, null, null)).path("csrf").asText();
        assertEquals(
            200, send(again, "POST", "/auth/signout", "{}", logoutCsrf, null).statusCode());
        assertEquals(401, send(again, "GET", "/api/v1/account", null, null, null).statusCode());
      } finally {
        for (String module : new ArrayList<>(processes.keySet())) stop(module);
        var groups =
            admin.listConsumerGroups().all().get(15, TimeUnit.SECONDS).stream()
                .map(ConsumerGroupListing::groupId)
                .filter(g -> g.startsWith(namespace + "."))
                .toList();
        if (!groups.isEmpty()) {
          await(
              () ->
                  admin
                      .describeConsumerGroups(groups)
                      .all()
                      .get(5, TimeUnit.SECONDS)
                      .values()
                      .stream()
                      .allMatch(g -> g.members().isEmpty()),
              "测试消费组退出");
          admin.deleteConsumerGroups(groups).all().get(15, TimeUnit.SECONDS);
        }
        admin.deleteTopics(topics).all().get(15, TimeUnit.SECONDS);
        var cursor = connection.sync().scan(ScanArgs.Builder.matches(namespace + ":*").limit(100));
        while (true) {
          if (!cursor.getKeys().isEmpty())
            connection.sync().del(cursor.getKeys().toArray(String[]::new));
          if (cursor.isFinished()) break;
          cursor =
              connection.sync().scan(cursor, ScanArgs.Builder.matches(namespace + ":*").limit(100));
        }
      }
    }
  }

  /** 仅在明确测试库清理白名单表；目录、迁移和开发库不动。 */
  private void reset() {
    for (String table :
        List.of(
            "demo_steps",
            "bootstrap_runs",
            "password_auths",
            "user_profiles",
            "users",
            "event_outbox",
            "engine_results",
            "api_requests",
            "engine_snapshots",
            "unique_events",
            "events",
            "orders",
            "match_details",
            "clearings",
            "transfer_logs",
            "ticks",
            "sec_bars",
            "min_bars",
            "hour_bars",
            "day_bars")) jdbc.update("DELETE FROM " + table);
    jdbc.update("UPDATE engine_output_checkpoint SET sequenceId=0 WHERE id=1");
    jdbc.update("UPDATE projection_offsets SET sequenceId=0 WHERE name='quotation'");
  }

  /** 私有浏览器上下文使用不同 Cookie 容器，不能用同一 Cookie 测试买卖身份。 */
  private HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .connectTimeout(Duration.ofSeconds(2))
        .build();
  }

  /** 真实 HTTP 验证 CSRF 与幂等头，响应保留上游原状态。 */
  private HttpResponse<String> send(
      HttpClient client, String method, String path, String body, String proof, String id)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base("ui") + path)).timeout(Duration.ofSeconds(12));
    if (proof != null) builder.header("X-CSRF-Token", proof);
    if (id != null) builder.header("Idempotency-Key", id);
    if (method.equals("POST"))
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body));
    else builder.GET();
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** 各进程使用随机端口、同一个随机测试命名空间，日志不含测试口令。 */
  private void start(String module, boolean demo) throws Exception {
    var builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms24m",
            "-Xmx112m",
            "-XX:MaxMetaspaceSize=160m",
            "-XX:ActiveProcessorCount=2",
            "-Dfile.encoding=UTF-8",
            "-jar",
            root.resolve(module + "/target/" + module + ".jar").toString(),
            "--server.port=" + ports.get(module),
            "--management.server.port=0",
            "--logging.level.root=WARN");
    var env = builder.environment();
    env.put("SECURITIES_JDBC_URL", System.getenv("SECURITIES_PHASE2_DB_URL"));
    env.put("SECURITIES_NAMESPACE", namespace);
    env.put("SECURITIES_AUTH_KEY", key);
    env.put("SEC_KAFKA_BOOTSTRAP_SERVERS", System.getenv("SECURITIES_TEST_BOOTSTRAP"));
    env.put("SEC_REDIS_URI", System.getenv("SECURITIES_TEST_REDIS_URI"));
    env.put("SECURITIES_ENGINE_URL", base("trading-engine"));
    env.put("SECURITIES_API_URL", base("trading-api"));
    env.put("SECURITIES_WEBSOCKET_URL", "ws://127.0.0.1:" + ports.get("push") + "/ws");
    env.put("SECURITIES_ALLOWED_ORIGINS", base("ui"));
    env.put("SECURITIES_DEMO_ENABLED", Boolean.toString(demo));
    env.put("SECURITIES_DEMO_BUYER_PASSWORD", buyerPassword);
    env.put("SECURITIES_DEMO_SELLER_PASSWORD", sellerPassword);
    for (int i = 1; i <= 10; i++)
      env.put(
          "SECURITIES_DEMO_USER" + i + "_PASSWORD",
          i == 1 ? buyerPassword : i == 2 ? sellerPassword : UUID.randomUUID().toString());
    env.put("SECURITIES_OUTPUT_SEND_ENABLED", "true");
    processes.put(
        module,
        builder
            .redirectErrorStream(true)
            .redirectOutput(logs.resolve(module + "-" + System.nanoTime() + ".log").toFile())
            .start());
  }

  /** 只停止本测试拥有的应用，不触碰用户已启动的 Java 服务。 */
  private void stop(String module) throws Exception {
    var process = processes.remove(module);
    if (process != null && process.isAlive()) {
      process.destroy();
      if (!process.waitFor(12, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(12, TimeUnit.SECONDS);
      }
    }
  }

  /** 可选的真正 Chromium 页面测试，不用 HTTP 测试冒充浏览器渲染。 */
  private void browser() throws Exception {
    var builder = new ProcessBuilder("node", root.resolve("ui/src/test/js/browser.mjs").toString());
    var env = builder.environment();
    env.put("SECURITIES_BROWSER_URL", base("ui"));
    env.put("SECURITIES_BROWSER_BUYER_PASSWORD", buyerPassword);
    env.put("SECURITIES_BROWSER_SELLER_PASSWORD", sellerPassword);
    env.put("SECURITIES_BROWSER_OUTPUT", logs.toString());
    var process =
        builder
            .redirectErrorStream(true)
            .redirectOutput(logs.resolve("browser.log").toFile())
            .start();
    try {
      assertTrue(process.waitFor(120, TimeUnit.SECONDS), "浏览器测试超时：" + logs);
      assertEquals(0, process.exitValue(), Files.readString(logs.resolve("browser.log")));
    } finally {
      if (process.isAlive()) {
        process
            .descendants()
            .forEach(
                child -> {
                    /* 超时只终止本测试 Node 的浏览器子进程，不按进程名称广泛清理。 */
                  child.destroyForcibly();
                });
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  /** JSON 仅在断言内部解析，密码不输出到测试日志。 */
  private JsonNode tree(HttpResponse<String> response) throws Exception {
    return json.readTree(response.body());
  }

  /** 十进制断言不能用 double 比较金融结果。 */
  private void amount(String expected, JsonNode value) {
    assertEquals(
        0, new java.math.BigDecimal(expected).compareTo(new java.math.BigDecimal(value.asText())));
  }

  /** 单行计数帮助等待实际数据库状态。 */
  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  /** 随机回环端口避免占用已运行的业务入口。 */
  private int port() throws Exception {
    try (var server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return server.getLocalPort();
    }
  }

  /** 固定本机目标，浏览器不能修改上游地址。 */
  private String base(String module) {
    return "http://127.0.0.1:" + ports.get(module);
  }

  /** 探测状态仅用于等待，不把端口打开视为交易完成。 */
  private int status(String module, String path) {
    try {
      return probe
          .send(
              HttpRequest.newBuilder(URI.create(base(module) + path))
                  .timeout(Duration.ofSeconds(2))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    } catch (Exception failure) {
      return 503;
    }
  }

  /** 有界等待可观察条件，失败提供进程日志目录。 */
  private void await(Check check, String description) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
    while (System.nanoTime() < deadline) {
      try {
        if (check.test()) return;
      } catch (Exception transientFailure) {
        /* 容许进程与投影短暂落后。 */
      }
      Thread.sleep(100);
    }
    fail(description + "超时，日志：" + logs);
  }

  private interface Check {
    /** 条件允许真实网络和数据库调用。 */
    boolean test() throws Exception;
  }
}
