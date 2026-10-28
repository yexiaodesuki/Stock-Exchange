/* 显式演示初始化：身份与步骤原子登记，命令走 Kafka 定序，引擎确认后推进；重启不重置资产。 */
package com.itranswarp.exchange.ui.demo;

import com.fasterxml.jackson.databind.*;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.*;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.output.*;
import com.itranswarp.exchange.securities.persistence.ProcessLease;
import com.itranswarp.exchange.ui.service.UserService;
import com.itranswarp.exchange.util.JsonUtil;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public final class DemoBootstrap implements AutoCloseable {
  static final String RUN = "demo-v2";
  static final int STEPS = 100;
  private final List<String> passwords;
  private final DataSource source;
  private final JdbcTemplate jdbc;
  private final UserService users;
  private final OutputStore outputs;
  private final String bootstrap, namespace, engine;
  private final boolean enabled;
  private final String buyerPassword, sellerPassword;
  private final TransactionTemplate transaction;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private volatile String state = "DISABLED", message = "未启用演示初始化";
  private volatile boolean running;
  private Thread thread;

  /** 未显式开启不配资；密码由本地配置提供，不能用公开固定口令。 */
  public DemoBootstrap(
      DataSource source,
      UserService users,
      String bootstrap,
      String namespace,
      String engine,
      boolean enabled,
      String buyerPassword,
      String sellerPassword) {
    this(
        source,
        users,
        bootstrap,
        namespace,
        engine,
        enabled,
        java.util.stream.IntStream.range(0, 10)
            .mapToObj(i -> i == 1 ? sellerPassword : buyerPassword)
            .toList());
  }

  /** 十个初始用户分别使用本地注入的口令；不根据业务重启重新生成密码。 */
  public DemoBootstrap(
      DataSource source,
      UserService users,
      String bootstrap,
      String namespace,
      String engine,
      boolean enabled,
      List<String> passwords) {
    this.source = source;
    this.users = users;
    this.bootstrap = bootstrap;
    this.namespace = namespace;
    this.engine = engine;
    this.enabled = enabled;
    this.passwords = List.copyOf(passwords);
    this.buyerPassword = "";
    this.sellerPassword = "";
    jdbc = new JdbcTemplate(source);
    outputs = new OutputStore(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  /** 后台工作不阻塞页面展示进度；失败保留现场，只有再次显式开启才续跑。 */
  public synchronized void start() {
    if (thread != null) throw new IllegalStateException("演示初始化已启动");
    if (!enabled) return;
    state = "RUNNING";
    message = "正在等待依赖并准备演示数据";
    running = true;
    thread = new Thread(this::run, "securities-demo-bootstrap");
    thread.setDaemon(true);
    thread.start();
  }

  /** 状态可公开但不能包含密码、账户令牌或内部异常堆栈。 */
  public Map<String, Object> status() {
    var rows = jdbc.queryForList("SELECT status FROM bootstrap_runs WHERE id=?", RUN);
    String persisted = rows.isEmpty() ? "NONE" : rows.get(0).get("status").toString();
    int complete =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM demo_steps WHERE runId=? AND status='COMPLETE'",
            Integer.class,
            RUN);
    return Map.of(
        "enabled",
        enabled,
        "state",
        enabled ? state : persisted.equals("NONE") ? "DISABLED" : persisted,
        "message",
        message,
        "completed",
        complete,
        "total",
        STEPS);
  }

  /** 半初始化环境禁止交易与注册；普通非演示环境不需要演示记录。 */
  public void requireComplete() {
    var rows =
        jdbc.queryForList("SELECT status FROM bootstrap_runs WHERE id=? OR id='demo-v1'", RUN);
    if ((enabled && !Set.of("COMPLETE", "LEGACY").contains(state))
        || rows.stream().anyMatch(row -> !row.get("status").equals("COMPLETE")))
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "初始化尚未完成，请检查状态");
  }

  /** 唯一初始化器先取得数据库会话锁，重启只补齐未完成步骤。 */
  private void run() {
    boolean acquired = false;
    try (var lease = new ProcessLease(source, "demo")) {
      acquired = true;
      if (complete()) {
        state = "COMPLETE";
        message = "演示已经完成，保留当前交易状态";
        return;
      }
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM bootstrap_runs WHERE id='demo-v1' AND status='COMPLETE'",
              Integer.class)
          > 0) {
        try (var credits =
            new com.itranswarp.exchange.ui.demo.DemoCreditService(
                source, bootstrap, namespace, engine)) {
          new com.itranswarp.exchange.ui.demo.DemoUserExpansionService(source, users, passwords)
              .expand(credits::execute);
        }
        state = "LEGACY";
        message = "已保留原账户并补齐十位演示用户，不会重复配资";
        return;
      }
      awaitReady();
      lease.check();
      prepare();
      checkOwnedEvents();
      var channels = new SecuritiesChannels(namespace);
      try (var producer =
          new KafkaProducer<String, String>(
              Map.of(
                  "bootstrap.servers",
                  bootstrap,
                  "acks",
                  "all",
                  "enable.idempotence",
                  true,
                  "max.block.ms",
                  3000,
                  "request.timeout.ms",
                  3000,
                  "delivery.timeout.ms",
                  6000,
                  "key.serializer",
                  "org.apache.kafka.common.serialization.StringSerializer",
                  "value.serializer",
                  "org.apache.kafka.common.serialization.StringSerializer"))) {
        for (var row :
            jdbc.queryForList("SELECT * FROM demo_steps WHERE runId=? ORDER BY stepId", RUN)) {
          if (!running) throw new InterruptedException();
          lease.check();
          checkOwnedEvents();
          String text = (String) row.get("submissionData");
          if (!OutputStore.hash(text).equals(row.get("commandHash")))
            throw new IllegalStateException("演示步骤内容损坏");
          var submission = JsonUtil.readJson(text, Submission.class);
          if (!submission.uniqueId().equals(row.get("stepId"))
              || submission.userId() != ((Number) row.get("userId")).longValue())
            throw new IllegalStateException("演示身份与步骤不一致");
          if (row.get("status").equals("COMPLETE")) {
            var result = outputs.result(submission.userId(), submission.uniqueId()).orElseThrow();
            validate(submission, result);
            continue;
          }
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90), nextSend = 0;
          boolean done = false;
          while (running && System.nanoTime() < deadline) {
            lease.check();
            var result = outputs.result(submission.userId(), submission.uniqueId());
            if (result.isPresent()
                && ready().path("applied").asLong() >= result.get().sequenceId()) {
              validate(submission, result.get());
              jdbc.update(
                  "UPDATE demo_steps SET sequenceId=?,status='COMPLETE',updatedAt=? WHERE stepId=?",
                  result.get().sequenceId(),
                  System.currentTimeMillis(),
                  submission.uniqueId());
              done = true;
              break;
            }
            if (System.nanoTime() >= nextSend) {
              boolean credit =
                  submission.command() instanceof CreditCash
                      || submission.command() instanceof CreditPosition;
              try {
                producer
                    .send(
                        new ProducerRecord<>(
                            channels.topic(credit ? "transfer" : "sequence"), text))
                    .get(8, TimeUnit.SECONDS);
              } catch (Exception uncertain) {
                /* 相同标识重试，不能因 Kafka 超时创建第二次配资。 */
              }
              nextSend = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            }
            Thread.sleep(100);
          }
          if (!done) throw new IllegalStateException("等待演示执行结果超时");
        }
      }
      lease.check();
      checkOwnedEvents();
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM demo_steps WHERE runId=? AND status='COMPLETE'",
              Integer.class,
              RUN)
          != STEPS) throw new IllegalStateException("演示步骤未完整结束");
      jdbc.update(
          "UPDATE bootstrap_runs SET status='COMPLETE',updatedAt=? WHERE id=?",
          System.currentTimeMillis(),
          RUN);
      state = "COMPLETE";
      message = "五证券演示交易已完成；之后启动不会重复配资";
    } catch (Exception failure) {
      // 未取得锁的竞争实例只能报告自己失败，不能改写另一个初始化器的运行记录。
      if (running) {
        state = "FAILED";
        message = "初始化未完成：请检查依赖、独占环境和步骤记录，再显式启动续跑";
        if (acquired)
          try {
            jdbc.update(
                "UPDATE bootstrap_runs SET status='FAILED',updatedAt=? WHERE id=? AND"
                    + " status<>'COMPLETE'",
                System.currentTimeMillis(),
                RUN);
          } catch (Exception unavailable) {
            /* 数据库不可用时保留步骤现场，不掩盖最初的失败。 */
          }
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("演示初始化暂停，原因类型：{}", failure.getClass().getSimpleName());
      }
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
    } finally {
      running = false;
    }
  }

  /** COMPLETE 是一次性标记，不用期初余额覆盖后来真实交易。 */
  private boolean complete() {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM bootstrap_runs WHERE id=? AND version=2 AND status='COMPLETE'",
            Integer.class,
            RUN)
        == 1;
  }

  /** 首次仅允许空业务库；十身份、运行和 100 步同事务提交，已有邮箱不会被接管。 */
  void prepare() {
    transaction.executeWithoutResult(
        status -> {
          var existing =
              jdbc.queryForList("SELECT * FROM bootstrap_runs WHERE id=? FOR UPDATE", RUN);
          if (!existing.isEmpty()) {
            if (((Number) existing.get(0).get("version")).intValue() != 2)
              throw new IllegalStateException("演示版本冲突");
            if (jdbc.queryForObject(
                    "SELECT COUNT(*) FROM demo_steps WHERE runId=?", Integer.class, RUN)
                != STEPS) throw new IllegalStateException("演示步骤缺失");
            jdbc.update(
                "UPDATE bootstrap_runs SET status='RUNNING',updatedAt=? WHERE id=?",
                System.currentTimeMillis(),
                RUN);
            return;
          }
          // 空库预检禁止把演示资金混入已有人使用的账户；绝不自动清空旧数据。
          for (String table :
              List.of("users", "events", "api_requests", "orders", "ticks", "bootstrap_runs"))
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class) != 0)
              throw new IllegalStateException("需要未使用的证券环境");
          if (passwords.size() != 10) throw new IllegalArgumentException("必须提供十个初始用户密码");
          String[] names = {"用户一", "用户二", "用户三", "用户四", "用户五", "用户六", "用户七", "用户八", "用户九", "用户十"};
          var initial = new ArrayList<UserService.User>();
          for (int i = 0; i < 10; i++)
            initial.add(
                users.create("user" + (i + 1) + "@example.com", names[i], passwords.get(i)));
          jdbc.update(
              "INSERT INTO bootstrap_runs VALUES (?,2,'RUNNING',?)",
              RUN,
              System.currentTimeMillis());
          int index = 0;
          long buy = Long.parseLong(initial.get(0).id()),
              sell = Long.parseLong(initial.get(1).id());
          for (var identity : initial) {
            long user = Long.parseLong(identity.id());
            step(
                ++index,
                user,
                null,
                new CreditCash(
                    com.itranswarp.exchange.securities.Currency.USD, new BigDecimal("100000")));
            for (long security = 1001; security <= 1005; security++)
              step(++index, user, security, new CreditPosition(security, new BigDecimal("1000")));
          }
          int[] prices = {100, 50, 80, 30, 60};
          for (int s = 0; s < 5; s++) {
            long security = 1001 + s;
            BigDecimal base = BigDecimal.valueOf(prices[s]);
            for (int trade = 0; trade < 3; trade++) {
              BigDecimal price =
                  base.add(new BigDecimal("0.50").multiply(BigDecimal.valueOf(trade)));
              step(
                  ++index,
                  sell,
                  security,
                  new PlaceOrder(security, Direction.SELL, price, new BigDecimal("5")));
              step(
                  ++index,
                  buy,
                  security,
                  new PlaceOrder(security, Direction.BUY, price, new BigDecimal("5")));
            }
            step(
                ++index,
                buy,
                security,
                new PlaceOrder(
                    security,
                    Direction.BUY,
                    base.subtract(new BigDecimal("2")),
                    new BigDecimal("10")));
            step(
                ++index,
                sell,
                security,
                new PlaceOrder(
                    security, Direction.SELL, base.add(new BigDecimal("3")), new BigDecimal("10")));
          }
          if (index != STEPS) throw new IllegalStateException("演示计划计数错误");
        });
  }

  /** 持久步骤含完整命令与校验和，失败重试使用完全相同的 uniqueId。 */
  private void step(int index, long user, Long security, Command command) {
    String id = RUN + ":" + String.format(Locale.ROOT, "%03d", index);
    String text = JsonUtil.writeJson(new Submission(2, 1, id, user, command));
    jdbc.update(
        "INSERT INTO demo_steps VALUES (?,?,?,?,?,?,NULL,'PENDING',?)",
        id,
        RUN,
        user,
        security,
        OutputStore.hash(text),
        text,
        System.currentTimeMillis());
  }

  /** 若有人绕过 UI 提交其他业务，停止演示而不是与未知订单继续混合。 */
  private void checkOwnedEvents() {
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM unique_events u LEFT JOIN demo_steps d ON d.stepId=u.uniqueId AND"
                + " d.runId=? WHERE d.stepId IS NULL",
            Integer.class,
            RUN)
        != 0) throw new IllegalStateException("演示环境出现其他业务事件");
  }

  /** 校验业务已接受且成交符合计划，未成交卖单与最终挂单不允许意外撮合。 */
  private void validate(Submission submission, ExecutionEnvelope result) {
    if (!result.accepted()) throw new IllegalStateException("演示命令被拒绝");
    if (submission.command() instanceof PlaceOrder place) {
      boolean seedBuy =
          place.direction() == Direction.BUY
              && place.quantity().compareTo(new BigDecimal("5")) == 0;
      if (result.trades().size() != (seedBuy ? 1 : 0))
        throw new IllegalStateException("演示撮合受到其他订单干扰");
      if (seedBuy) {
        var trade = result.trades().get(0);
        var seller =
            jdbc.queryForObject(
                "SELECT userId FROM user_profiles WHERE email='user2@example.com'", Long.class);
        if (trade.buyerId() != submission.userId()
            || trade.sellerId() != seller
            || trade.price().compareTo(place.price()) != 0
            || trade.quantity().compareTo(place.quantity()) != 0)
          throw new IllegalStateException("演示成交身份、价格或数量不符");
      }
    }
  }

  /** 初始化先等待引擎恢复完成；定序未启动时步骤超时会保留 PENDING。 */
  private void awaitReady() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    while (running && System.nanoTime() < deadline) {
      try {
        ready();
        return;
      } catch (Exception unavailable) {
        Thread.sleep(200);
      }
    }
    throw new IllegalStateException("引擎未就绪");
  }

  /** 读取实际引擎已应用位置，不仅依赖持久输出已写入。 */
  private JsonNode ready() throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(engine + "/internal/securities/status"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new IllegalStateException("引擎恢复中");
    return json.readTree(response.body());
  }

  /** 停止后台初始化不撤销已提交事件，剩余步骤留给下次显式续跑。 */
  @Override
  public synchronized void close() {
    running = false;
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join(10000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
