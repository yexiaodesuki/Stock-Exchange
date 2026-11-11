/* 真实进程联调：专属临时库/Topic，强制终止证券引擎后恢复并继续成交撤单；不访问开发账户。 */
package com.itranswarp.exchange;

import static com.itranswarp.exchange.SecuritiesCoreTest.*;
import static com.itranswarp.exchange.SecuritiesPersistenceTest.request;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itranswarp.exchange.core.SecuritiesEngine;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.*;
import com.itranswarp.exchange.securities.Currency;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.persistence.EventJournal;
import com.itranswarp.exchange.snapshot.SnapshotRepository;
import com.itranswarp.exchange.util.JsonUtil;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "SECURITIES_PHASE2_DB_URL", matches = ".+")
class SecuritiesProcessTest {
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  final ObjectMapper mapper = new ObjectMapper();

  /** 真正启动两个新包，使用实际 Kafka 输入；从有效快照加未保存尾部恢复后继续交易。 */
  @Test
  void survivesForcedTerminationAndContinuesTrading() throws Exception {
    var source = SecuritiesPersistenceTest.source();
    var jdbc = new JdbcTemplate(source);
    TestDatabase.reset(source);
    var catalog = SecurityCatalog.load(source);
    var journal = new EventJournal(source, catalog);
    String bootstrap = System.getenv("SECURITIES_TEST_BOOTSTRAP");
    String namespace = "securities.test." + UUID.randomUUID().toString().replace("-", "");
    var channels = new SecuritiesChannels(namespace);
    var logs = Files.createTempDirectory("securities-phase2-process-");
    System.out.println("证券真实进程日志目录：" + logs);
    int port = port();
    Process engine = null, sequencer = null;
    var topics =
        List.of(
            channels.topic("sequence"),
            channels.topic("transfer"),
            channels.topic("trade"),
            channels.topic("tick"));
    try (var admin =
            AdminClient.create(
                Map.of(
                    "bootstrap.servers",
                    bootstrap,
                    "default.api.timeout.ms",
                    10000,
                    "request.timeout.ms",
                    5000));
        var producer =
            new KafkaProducer<String, String>(
                Map.of(
                    "bootstrap.servers",
                    bootstrap,
                    "acks",
                    "all",
                    "key.serializer",
                    "org.apache.kafka.common.serialization.StringSerializer",
                    "value.serializer",
                    "org.apache.kafka.common.serialization.StringSerializer"))) {
      admin
          .createTopics(topics.stream().map(t -> new NewTopic(t, 1, (short) 1)).toList())
          .all()
          .get(15, TimeUnit.SECONDS);
      try {
        engine = start("trading-engine", port, namespace, logs.resolve("engine-first.log"));
        Process firstEngine = engine;
        await(() -> firstEngine.isAlive() && status(port) == 200, "证券引擎启动就绪");
        sequencer = start("trading-sequencer", port(), namespace, logs.resolve("sequencer.log"));
        send(producer, channels, "cash", 2, new CreditCash(Currency.USD, n("10000")));
        for (long security = 1001; security <= 1005; security++)
          send(producer, channels, "shares:" + security, 3, new CreditPosition(security, n("100")));
        await(() -> applied(port) >= 6, "六条配给事件执行");
        for (long security = 1001; security <= 1005; security++) {
          send(
              producer,
              channels,
              "sell:" + security,
              3,
              new PlaceOrder(security, Direction.SELL, n("20"), n("10")));
          send(
              producer,
              channels,
              "buy:" + security,
              2,
              new PlaceOrder(security, Direction.BUY, n("21"), n("4")));
        }
        await(() -> applied(port) == 16, "五证券部分成交");
        amount("9600", account(port, 2).get("cash").get("available").decimalValue());
        // 用同一持久事件独立重放后保存明确切点，随后制造未入快照的尾部事件。
        var checkpoint = new SecuritiesEngine(catalog);
        journal.page(0, 16, 100).forEach(e -> checkpoint.execute(e, SecuritiesEngine.Mode.REPLAY));
        new SnapshotRepository(source, catalog).save(checkpoint.snapshot());
        send(
            producer, channels, "pending", 2, new PlaceOrder(1001, Direction.BUY, n("10"), n("1")));
        await(() -> applied(port) == 17, "快照后的挂单冻结");
        amount("10", account(port, 2).get("cash").get("frozen").decimalValue());
        assertEquals(
            16, jdbc.queryForObject("SELECT MAX(sequenceId) FROM engine_snapshots", Long.class));
        engine.destroyForcibly();
        assertTrue(engine.waitFor(15, TimeUnit.SECONDS));
        engine = start("trading-engine", port, namespace, logs.resolve("engine-restored.log"));
        await(() -> status(port) == 200 && applied(port) == 17, "强制终止后快照加增量恢复");
        amount("9590", account(port, 2).get("cash").get("available").decimalValue());
        amount("10", account(port, 2).get("cash").get("frozen").decimalValue());
        // 同一配给重发不能再次增加资金，也不能产生新事件。
        send(producer, channels, "cash", 2, new CreditCash(Currency.USD, n("10000.00")));
        send(
            producer,
            channels,
            "continue",
            2,
            new PlaceOrder(1001, Direction.BUY, n("25"), n("6")));
        await(() -> applied(port) == 18, "恢复后的原卖单继续成交");
        send(producer, channels, "cancel", 2, new CancelOrder(1001, 17));
        await(() -> applied(port) == 19, "恢复后的原买单可撤销");
        amount("9480", account(port, 2).get("cash").get("available").decimalValue());
        amount("0", account(port, 2).get("cash").get("frozen").decimalValue());
        assertEquals(19, journal.latest());
        var positions = account(port, 2).get("positions");
        for (var position : positions)
          amount(
              position.get("securityId").asLong() == 1001 ? "10" : "4",
              position.get("available").decimalValue());
        JsonNode market = get(port, "/internal/securities/markets/1001");
        assertTrue(market.get("orders").isEmpty());
      } finally {
        stop(engine);
        stop(sequencer);
        // 只清理本测试随机命名的消费组和四个 Topic，不重置任何开发组。
        try {
          var owned =
              admin.listConsumerGroups().all().get(15, TimeUnit.SECONDS).stream()
                  .map(ConsumerGroupListing::groupId)
                  .filter(
                      g ->
                          g.equals(channels.group("engine"))
                              || g.equals(channels.group("sequencer")))
                  .toList();
          if (!owned.isEmpty()) {
            // 强制终止的旧成员需要等会话超时，不能立即删组并把清理错误当成资金故障。
            await(
                () ->
                    admin
                        .describeConsumerGroups(owned)
                        .all()
                        .get(5, TimeUnit.SECONDS)
                        .values()
                        .stream()
                        .allMatch(group -> group.members().isEmpty()),
                "本次测试消费组退出");
            admin.deleteConsumerGroups(owned).all().get(15, TimeUnit.SECONDS);
          }
        } finally {
          admin.deleteTopics(topics).all().get(15, TimeUnit.SECONDS);
        }
      }
    }
  }

  /** 在内部专属 Topic 发布测试命令，配给与订单分开路由。 */
  private void send(
      KafkaProducer<String, String> producer,
      SecuritiesChannels channels,
      String id,
      long user,
      Command command)
      throws Exception {
    boolean credit = command instanceof CreditCash || command instanceof CreditPosition;
    producer
        .send(
            new ProducerRecord<>(
                channels.topic(credit ? "transfer" : "sequence"),
                JsonUtil.writeJson(request(id, user, command))))
        .get(10, TimeUnit.SECONDS);
  }

  /** 为测试 JVM 选择空闲端口，不覆盖用户已启动应用。 */
  private int port() throws Exception {
    try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  /** 启动且只跟踪自己创建的 JVM；凭据仅通过进程环境传递，日志不输出参数。 */
  private Process start(String module, int port, String namespace, Path log) throws Exception {
    Path root = Path.of(System.getenv("SECURITIES_TEST_ROOT"));
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var builder =
        new ProcessBuilder(
            java,
            "-Xms32m",
            "-Xmx128m",
            "-XX:MaxMetaspaceSize=160m",
            "-XX:ActiveProcessorCount=2",
            "-Dfile.encoding=UTF-8",
            "-jar",
            root.resolve(module + "/target/" + module + ".jar").toString(),
            "--server.port=" + port,
            "--management.server.port=0",
            "--logging.level.root=WARN");
    builder.environment().put("SECURITIES_JDBC_URL", System.getenv("SECURITIES_PHASE2_DB_URL"));
    builder.environment().put("SECURITIES_NAMESPACE", namespace);
    builder
        .environment()
        .put("SEC_KAFKA_BOOTSTRAP_SERVERS", System.getenv("SECURITIES_TEST_BOOTSTRAP"));
    builder.environment().put("SECURITIES_SNAPSHOT_INTERVAL_MS", "3600000");
    builder.environment().put("SECURITIES_OUTPUT_SEND_ENABLED", "false");
    return builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
  }

  /** 关闭只属于此测试的进程；失败时强制终止以释放本次临时库实例锁。 */
  private void stop(Process process) throws Exception {
    if (process == null || !process.isAlive()) return;
    process.destroy();
    if (!process.waitFor(12, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(12, TimeUnit.SECONDS);
    }
  }

  /** 读取只读端点并解析 JSON，短超时允许恢复期间重试。 */
  private JsonNode get(int port, String path) throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IllegalStateException("端点未就绪：" + response.statusCode());
    return mapper.readTree(response.body());
  }

  /** 状态端点不可达视为未就绪，不伪造成功。 */
  private int status(int port) {
    try {
      get(port, "/internal/securities/status");
      return 200;
    } catch (Exception unavailable) {
      return 503;
    }
  }

  /** 获取已完整应用位置，不能用数据库最大序号代替。 */
  private long applied(int port) throws Exception {
    return get(port, "/internal/securities/status").get("applied").asLong();
  }

  /** 查询账户必须收到 200，不允许 {} 冒充恢复完成。 */
  private JsonNode account(int port, long user) throws Exception {
    return get(port, "/internal/securities/accounts/" + user);
  }

  /** 在期限内等待真实断言满足，不用固定长睡眠猜测服务已启动。 */
  private void await(Check condition, String description) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(80);
    while (System.nanoTime() < deadline) {
      try {
        if (condition.test()) return;
      } catch (Exception transientFailure) {
        /* 启动/恢复时连接不可达允许重试。 */
      }
      Thread.sleep(100);
    }
    fail("等待超时：" + description);
  }

  private interface Check {
    /** 可抛异常的条件检查，等待器区分暂时不可达与最终超时。 */
    boolean test() throws Exception;
  }
}
