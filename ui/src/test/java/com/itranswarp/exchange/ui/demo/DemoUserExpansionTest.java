/* 旧演示用户补齐集成回归：仅使用随机临时库，验证断点、事务、身份保护和配资幂等。 */
package com.itranswarp.exchange.ui.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.*;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.output.*;
import com.itranswarp.exchange.securities.persistence.EventJournal;
import com.itranswarp.exchange.ui.service.UserService;
import com.itranswarp.exchange.util.JsonUtil;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "SECURITIES_PHASE2_DB_URL", matches = ".+")
class DemoUserExpansionTest {
  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private UserService users;
  private EventJournal journal;
  private OutputStore outputs;
  private long buyer, seller;
  private List<String> passwords;
  private static final String OLD_PASSWORD = "test-only-legacy-password-12345";

  /** 拒绝开发库；创建真实旧身份、配资流水和订单，不依赖固定用户 ID 或清空业务卷。 */
  @BeforeEach
  void prepare() {
    String url = System.getenv("SECURITIES_PHASE2_DB_URL");
    if (!url.matches("jdbc:mysql://[^/]+/exchange_securities_test_[a-f0-9]{32}\\?.+"))
      throw new IllegalStateException("拒绝使用非隔离测试库");
    source =
        new DriverManagerDataSource(url, "securities", System.getenv("SECURITIES_DB_PASSWORD"));
    jdbc = new JdbcTemplate(source);
    var catalog = SecurityCatalog.load(source);
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
    users = new UserService(source);
    buyer = Long.parseLong(users.create("buyer@example.com", "演示买方", OLD_PASSWORD).id());
    seller = Long.parseLong(users.create("seller@example.com", "演示卖方", OLD_PASSWORD).id());
    jdbc.update("INSERT INTO bootstrap_runs VALUES ('demo-v1',1,'COMPLETE',1)");
    journal = new EventJournal(source, catalog);
    outputs = new OutputStore(source);
    execute(
        new Submission(
            2,
            1,
            "old:cash",
            buyer,
            new CreditCash(
                com.itranswarp.exchange.securities.Currency.USD, new BigDecimal("12345"))));
    execute(
        new Submission(2, 1, "old:shares", seller, new CreditPosition(1001, new BigDecimal("23"))));
    execute(
        new Submission(
            2,
            1,
            "old:sell",
            seller,
            new PlaceOrder(1001, Direction.SELL, new BigDecimal("100"), new BigDecimal("10"))));
    passwords =
        java.util.stream.IntStream.rangeClosed(1, 10)
            .mapToObj(i -> "test-only-user-password-" + i + "-12345")
            .toList();
  }

  /** 受控执行替身只验证协调与持久事务；真实引擎余额另由联调验证，不引入 UI 对引擎模块的依赖。 */
  private ExecutionEnvelope execute(Submission request) {
    var existing = outputs.result(request.userId(), request.uniqueId());
    if (existing.isPresent()) return existing.get();
    var event = journal.append(request);
    var orders = new ArrayList<ExecutionEnvelope.OrderChange>();
    if (request.command() instanceof PlaceOrder place)
      orders.add(
          new ExecutionEnvelope.OrderChange(
              event.sequenceId(),
              event.sequenceId(),
              event.userId(),
              place.securityId(),
              place.direction(),
              place.price(),
              place.quantity(),
              place.quantity(),
              "PENDING",
              event.createdAt(),
              event.createdAt()));
    var result =
        new ExecutionEnvelope(
            event.sequenceId(),
            event.previousId(),
            event.userId(),
            event.uniqueId(),
            event.createdAt(),
            true,
            "OK",
            orders.isEmpty() ? null : event.sequenceId(),
            orders,
            List.of(),
            List.of());
    outputs.save(event, result, false);
    return result;
  }

  /** 补齐后检查配资申请流水及旧身份/订单，事后有新订单也不重新发送任何配资。 */
  @Test
  void expandsAndDoesNotResetAnyAccountOnRepeat() throws Exception {
    String beforeBuyer = legacyState(buyer), beforeSeller = legacyState(seller);
    String oldOrders = JsonUtil.writeJson(jdbc.queryForList("SELECT * FROM orders ORDER BY id"));
    var service = new DemoUserExpansionService(source, users, passwords);
    service.expand(this::execute);
    assertEquals(10, count("SELECT COUNT(*) FROM users"));
    assertEquals(51, count("SELECT COUNT(*) FROM events"));
    assertEquals(
        48,
        count("SELECT COUNT(*) FROM demo_steps WHERE runId='demo-users-v3' AND status='COMPLETE'"));
    assertEquals(beforeBuyer, legacyState(buyer));
    assertEquals(beforeSeller, legacyState(seller));
    assertEquals(
        oldOrders, JsonUtil.writeJson(jdbc.queryForList("SELECT * FROM orders ORDER BY id")));
    assertEquals(Long.toString(buyer), users.authenticate("buyer@example.com", OLD_PASSWORD).id());
    assertEquals(
        Long.toString(seller), users.authenticate("seller@example.com", OLD_PASSWORD).id());
    for (int slot = 3; slot <= 10; slot++) {
      var identity = users.authenticate("user" + slot + "@example.com", passwords.get(slot - 1));
      long user = Long.parseLong(identity.id());
      assertEquals(
          0,
          new BigDecimal("100000")
              .compareTo(
                  jdbc.queryForObject(
                      "SELECT SUM(amount) FROM transfer_logs WHERE userId=? AND assetKind='CASH'",
                      BigDecimal.class,
                      user)));
      for (long security = 1001; security <= 1005; security++)
        assertEquals(
            0,
            new BigDecimal("1000")
                .compareTo(
                    jdbc.queryForObject(
                        "SELECT SUM(amount) FROM transfer_logs WHERE userId=? AND"
                            + " assetKind='POSITION' AND securityId=?",
                        BigDecimal.class,
                        user,
                        security)));
    }
    long newcomer = Long.parseLong(users.authenticate("user3@example.com", passwords.get(2)).id());
    execute(
        new Submission(
            2,
            1,
            "new:order",
            newcomer,
            new PlaceOrder(1002, Direction.BUY, BigDecimal.ONE, BigDecimal.ONE)));
    String changed = legacyState(newcomer);
    service.expand(
        request -> {
          throw new AssertionError("完成后不应再次提交命令");
        });
    assertEquals(52, count("SELECT COUNT(*) FROM events"));
    assertEquals(changed, legacyState(newcomer));
  }

  /** 第七笔资金已入账但步骤未标记时故障，续跑必须仅补标记而不是再次配资。 */
  @Test
  void resumesAfterPersistedCreditBeforeStepAcknowledgement() throws Exception {
    var service = new DemoUserExpansionService(source, users, passwords);
    int[] sent = {0};
    assertThrows(
        IllegalStateException.class,
        () ->
            service.expand(
                request -> {
                  var result = execute(request);
                  if (++sent[0] == 7) throw new IllegalStateException("模拟确认前中断");
                  return result;
                }));
    assertEquals(10, count("SELECT COUNT(*) FROM users"));
    assertEquals(10, count("SELECT COUNT(*) FROM events"));
    assertEquals(6, count("SELECT COUNT(*) FROM demo_steps WHERE status='COMPLETE'"));
    service.expand(this::execute);
    assertEquals(51, count("SELECT COUNT(*) FROM events"));
    assertEquals(48, count("SELECT COUNT(*) FROM demo_steps WHERE status='COMPLETE'"));
  }

  /** 最后一位密码错误导致身份与整个计划回滚，不留下已创建的七个用户。 */
  @Test
  void invalidPasswordRollsBackEntirePlanAndCanRetry() throws Exception {
    var broken = new ArrayList<>(passwords);
    broken.set(9, "short");
    assertThrows(
        IllegalArgumentException.class,
        () -> new DemoUserExpansionService(source, users, broken).expand(this::execute));
    assertEquals(2, count("SELECT COUNT(*) FROM users"));
    assertEquals(0, count("SELECT COUNT(*) FROM demo_steps"));
    assertEquals(3, count("SELECT COUNT(*) FROM events"));
    new DemoUserExpansionService(source, users, passwords).expand(this::execute);
    assertEquals(10, count("SELECT COUNT(*) FROM users"));
  }

  /** 已经由他人注册的同名邮箱不能被接管、重置密码或自动配资。 */
  @Test
  void refusesNonLegacyTwoUserEnvironment() {
    users.create("user3@example.com", "自行注册的人", OLD_PASSWORD);
    assertThrows(
        IllegalStateException.class,
        () -> new DemoUserExpansionService(source, users, passwords).expand(this::execute));
    assertEquals(3, count("SELECT COUNT(*) FROM users"));
    assertEquals(0, count("SELECT COUNT(*) FROM demo_steps"));
    assertEquals(3, count("SELECT COUNT(*) FROM events"));
    assertEquals("自行注册的人", users.authenticate("user3@example.com", OLD_PASSWORD).name());
  }

  /** 只读断言固定的测试 SQL，不接收外部表名。 */
  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  /** 固定身份下的原始资料、密码哈希、配资和订单必须保持不变，结果只用于断言不输出。 */
  private String legacyState(long user) {
    return JsonUtil.writeJson(
        List.of(
            jdbc.queryForList("SELECT * FROM user_profiles WHERE userId=?", user),
            jdbc.queryForList("SELECT * FROM password_auths WHERE userId=?", user),
            jdbc.queryForList(
                "SELECT * FROM transfer_logs WHERE userId=? ORDER BY transferId", user),
            jdbc.queryForList("SELECT * FROM orders WHERE userId=? ORDER BY id", user)));
  }
}
