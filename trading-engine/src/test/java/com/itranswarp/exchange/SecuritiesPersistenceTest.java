/* 临时证券库集成测试：事务、幂等、锁、损坏快照和无 Kafka 提示追赶；禁止使用开发库。 */
package com.itranswarp.exchange;

import static com.itranswarp.exchange.SecuritiesCoreTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.itranswarp.exchange.core.*;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.*;
import com.itranswarp.exchange.securities.Currency;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "SECURITIES_PHASE2_DB_URL", matches = ".+")
class SecuritiesPersistenceTest {
  DataSource source;
  JdbcTemplate jdbc;
  SecurityCatalog catalog;
  EventJournal journal;

  /** 只允许随机临时库；清理的是本轮测试库中的事件，不影响开发环境。 */
  @BeforeEach
  void setup() {
    source = source();
    jdbc = new JdbcTemplate(source);
    catalog = SecurityCatalog.load(source);
    journal = new EventJournal(source, catalog);
    TestDatabase.reset(source);
  }

  /** 运行器提供临时库和凭据，缺失时绝不回退到 exchange_securities。 */
  static DataSource source() {
    String url = System.getenv("SECURITIES_PHASE2_DB_URL");
    if (url == null
        || !url.matches("jdbc:mysql://[^/]+/exchange_securities_test_[a-f0-9]{32}\\?.+"))
      throw new IllegalStateException("必须使用隔离临时库");
    return new DriverManagerDataSource(url, "securities", System.getenv("SECURITIES_DB_PASSWORD"));
  }

  /** 生成内部提交，业务唯一标识由测试显式控制。 */
  static Submission request(String id, long user, Command command) {
    return new Submission(2, 1, id, user, command);
  }

  /** 相同内容重试返回旧事件，金额尾零不同仍等价；换用户或金额拒绝。 */
  @Test
  void journalDeduplicatesAndRejectsConflictingReuse() {
    var first = journal.append(request("credit", 2, new CreditCash(Currency.USD, n("100.00"))));
    assertEquals(
        first, journal.append(request("credit", 2, new CreditCash(Currency.USD, n("100.0")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> journal.append(request("credit", 3, new CreditCash(Currency.USD, n("100")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> journal.append(request("credit", 2, new CreditCash(Currency.USD, n("101")))));
    assertEquals(1, journal.latest());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM unique_events", Integer.class));
  }

  /** 多调用并发由同一元数据行锁串行化，不能分配重复序号或分叉前序。 */
  @Test
  void serializesConcurrentAppend() throws Exception {
    var executor = Executors.newFixedThreadPool(4);
    try {
      var tasks = new ArrayList<Callable<SequencedEvent>>();
      for (int index = 0; index < 12; index++) {
        final int id = index;
        tasks.add(
            () ->
                journal.append(request("parallel:" + id, 2, new CreditCash(Currency.USD, n("1")))));
      }
      for (var future : executor.invokeAll(tasks)) future.get();
    } finally {
      executor.shutdownNow();
    }
    var events = journal.page(0, 12, 100);
    assertEquals(12, events.size());
    long previous = 0;
    for (var event : events) {
      assertEquals(previous, event.previousId());
      previous = event.sequenceId();
    }
  }

  /** 第二次写幂等表故障必须回滚先前事件 INSERT，不留下半个定序事务。 */
  @Test
  void rollsBackEventWhenUniqueInsertFails() {
    var failing =
        new org.springframework.jdbc.datasource.AbstractDataSource() {
          /** 包装真实连接，仅故障注入指定 INSERT，其他事务操作照常调用驱动。 */
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            return intercept(source.getConnection(), "INSERT INTO unique_events");
          }

          /** 测试数据源不使用单独用户名连接入口。 */
          @Override
          public java.sql.Connection getConnection(String user, String password)
              throws java.sql.SQLException {
            return getConnection();
          }
        };
    var broken = new EventJournal(failing, catalog);
    assertThrows(
        RuntimeException.class,
        () -> broken.append(request("fail", 2, new CreditCash(Currency.USD, n("1")))));
    assertEquals(0, journal.latest());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM unique_events", Integer.class));
  }

  /** 驱动代理在唯一键 INSERT 执行时抛异常，验证事务模板真正执行了回滚。 */
  private java.sql.Connection intercept(java.sql.Connection actual, String insertPrefix) {
    return (java.sql.Connection)
        java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class[] {java.sql.Connection.class},
            (proxy, method, args) -> {
              try {
                Object value = method.invoke(actual, args);
                if (method.getName().equals("prepareStatement")
                    && ((String) args[0]).startsWith(insertPrefix)) {
                  var statement = (java.sql.PreparedStatement) value;
                  // 只在 executeUpdate 故障，连接的 rollback/close 保持真实行为。
                  return java.lang.reflect.Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class[] {java.sql.PreparedStatement.class},
                      (p, m, a) -> {
                        if (m.getName().equals("executeUpdate"))
                          throw new java.sql.SQLException("测试唯一记录提交前故障");
                        try {
                          return m.invoke(statement, a);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                          throw failure.getCause();
                        }
                      });
                }
                return value;
              } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  /** 错误 JSON 标识不能被当作正常历史推进。 */
  @Test
  void detectsCorruptEventColumns() {
    journal.append(request("one", 2, new CreditCash(Currency.USD, n("1"))));
    jdbc.update("UPDATE events SET createdAt=createdAt+1 WHERE sequenceId=1");
    assertThrows(IllegalStateException.class, () -> journal.page(0, 1, 10));
  }

  /** 没有 Kafka 消息也执行已提交尾部；新运行时恢复订单且不重复冻结。 */
  @Test
  void runtimeCatchesUnnotifiedTailAndRestoresOrders() {
    journal.append(request("cash", 2, new CreditCash(Currency.USD, n("1000"))));
    SecuritiesSnapshot expected;
    try (var runtime = new TradingEngineService(source, catalog, 3600000)) {
      assertThrows(IllegalStateException.class, () -> runtime.account(2));
      runtime.start();
      journal.append(request("order", 2, new PlaceOrder(1001, Direction.BUY, n("10"), n("5"))));
      runtime.poll();
      amount("950", runtime.account(2).cash().available());
      amount("50", runtime.account(2).cash().frozen());
      runtime.save();
      expected = runtime.state();
      try (var ignored = new ProcessLease(source, "engine")) {
        fail("不应允许第二引擎实例");
      } catch (IllegalStateException correct) {
        /* 锁拒绝第二实例。 */
      }
    }
    journal.append(request("more", 2, new CreditCash(Currency.USD, n("10"))));
    try (var runtime = new TradingEngineService(source, catalog, 3600000)) {
      runtime.start();
      amount("960", runtime.account(2).cash().available());
      amount("50", runtime.account(2).cash().frozen());
      assertEquals(expected.orders(), runtime.state().orders());
      journal.append(request("cancel", 2, new CancelOrder(1001, expected.orders().get(0).id())));
      runtime.poll();
      amount("1010", runtime.account(2).cash().available());
      amount("0", runtime.account(2).cash().frozen());
    }
  }

  /** 最新快照损坏可回退；所有快照损坏可重放，真正缺失历史则不能 READY。 */
  @Test
  void fallsBackFromCorruptSnapshotsButRejectsMissingHistory() {
    journal.append(request("cash", 2, new CreditCash(Currency.USD, n("100"))));
    try (var runtime = new TradingEngineService(source, catalog, 3600000)) {
      runtime.start();
    }
    jdbc.update("UPDATE engine_snapshots SET checksum=REPEAT('0',64)");
    try (var runtime = new TradingEngineService(source, catalog, 3600000)) {
      runtime.start();
      amount("100", runtime.account(2).cash().available());
    }
    jdbc.update("UPDATE engine_snapshots SET checksum=REPEAT('0',64)");
    journal.append(request("next", 2, new CreditCash(Currency.USD, n("10"))));
    jdbc.update("DELETE FROM unique_events WHERE sequenceId=1");
    jdbc.update("DELETE FROM events WHERE sequenceId=1");
    try (var runtime = new TradingEngineService(source, catalog, 3600000)) {
      assertThrows(IllegalStateException.class, runtime::start);
      assertEquals(TradingEngineService.Status.FAILED, runtime.status());
      assertThrows(IllegalStateException.class, () -> runtime.account(2));
    }
  }

  /** 快照提交失败不记录已保存位置；恢复启动失败必须保持不可用。 */
  @Test
  void startupSnapshotFailurePreventsReady() {
    var failing =
        new org.springframework.jdbc.datasource.AbstractDataSource() {
          /** 只在快照 INSERT 时失败，启动读表及实例锁仍使用真实数据库。 */
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            return intercept(source.getConnection(), "INSERT INTO engine_snapshots");
          }

          /** 测试不使用用户名覆盖入口。 */
          @Override
          public java.sql.Connection getConnection(String user, String password)
              throws java.sql.SQLException {
            return getConnection();
          }
        };
    try (var runtime = new TradingEngineService(failing, catalog, 10)) {
      assertThrows(RuntimeException.class, runtime::start);
      assertEquals(TradingEngineService.Status.FAILED, runtime.status());
      assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM engine_snapshots", Integer.class));
    }
  }

  /** 周期保存只在有新事件时追加；无变化检查不得制造重复快照。 */
  @Test
  void periodicSnapshotRequiresNewEvents() throws Exception {
    try (var runtime = new TradingEngineService(source, catalog, 1)) {
      runtime.start();
      Thread.sleep(5);
      runtime.poll();
      assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM engine_snapshots", Integer.class));
      journal.append(request("periodic", 2, new CreditCash(Currency.USD, n("1"))));
      runtime.poll();
      assertEquals(
          1, jdbc.queryForObject("SELECT MAX(sequenceId) FROM engine_snapshots", Long.class));
      int count = jdbc.queryForObject("SELECT COUNT(*) FROM engine_snapshots", Integer.class);
      Thread.sleep(5);
      runtime.poll();
      assertEquals(
          count, jdbc.queryForObject("SELECT COUNT(*) FROM engine_snapshots", Integer.class));
    }
  }

  /** 即使恢复线程正阻塞在数据库，账户查询也应立即拒绝而非等待锁后返回正常余额。 */
  @Test
  void recoveringAccountQueryFailsImmediately() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var slow =
        new org.springframework.jdbc.datasource.AbstractDataSource() {
          /** 只延迟快照候选查询，不延迟构造阶段的版本检查。 */
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            var actual = source.getConnection();
            return (java.sql.Connection)
                java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[] {java.sql.Connection.class},
                    (p, m, a) -> {
                      if (m.getName().equals("prepareStatement")
                          && ((String) a[0]).contains("FROM engine_snapshots WHERE")) {
                        entered.countDown();
                        if (!release.await(10, TimeUnit.SECONDS))
                          throw new java.sql.SQLException("测试恢复屏障超时");
                      }
                      try {
                        return m.invoke(actual, a);
                      } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
          }

          /** 不支持独立的用户名覆盖路径。 */
          @Override
          public java.sql.Connection getConnection(String user, String password)
              throws java.sql.SQLException {
            return getConnection();
          }
        };
    var executor = Executors.newSingleThreadExecutor();
    var runtime = new TradingEngineService(slow, catalog, 3600000);
    try {
      var startup = executor.submit(runtime::start);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(1),
          () -> assertThrows(IllegalStateException.class, () -> runtime.account(2)));
      release.countDown();
      startup.get(10, TimeUnit.SECONDS);
      assertEquals(TradingEngineService.Status.READY, runtime.status());
    } finally {
      release.countDown();
      runtime.close();
      executor.shutdownNow();
    }
  }
}
