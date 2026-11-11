/* 第三阶段真实临时库投影测试：输出先提交、行情原子性、历史补齐和恢复不重复。 */
package com.itranswarp.exchange;

import static com.itranswarp.exchange.SecuritiesCoreTest.*;
import static com.itranswarp.exchange.SecuritiesPersistenceTest.request;
import static org.junit.jupiter.api.Assertions.*;

import com.itranswarp.exchange.core.*;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.*;
import com.itranswarp.exchange.securities.Currency;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.output.*;
import com.itranswarp.exchange.snapshot.SnapshotRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "SECURITIES_PHASE2_DB_URL", matches = ".+")
class SecuritiesOutputTest {
  javax.sql.DataSource source;
  JdbcTemplate jdbc;
  SecurityCatalog catalog;

  /** 只复位随机临时库，不改开发用户或资产。 */
  @BeforeEach
  void setup() {
    source = SecuritiesPersistenceTest.source();
    jdbc = new JdbcTemplate(source);
    TestDatabase.reset(source);
    catalog = SecurityCatalog.load(source);
  }

  /** 制作确定性的五证券历史，每证券同一时段成交两笔，用于验证重复不会翻倍。 */
  private List<SequencedEvent> history() {
    var list = new ArrayList<SequencedEvent>();
    long seq = 0;
    list.add(
        new SequencedEvent(
            2, 1, ++seq, seq - 1, "cash", 2, 60001, new CreditCash(Currency.USD, n("10000"))));
    for (long security = 1001; security <= 1005; security++) {
      list.add(
          new SequencedEvent(
              2,
              1,
              ++seq,
              seq - 1,
              "shares:" + security,
              3,
              60001,
              new CreditPosition(security, n("100"))));
      list.add(
          new SequencedEvent(
              2,
              1,
              ++seq,
              seq - 1,
              "sell:" + security,
              3,
              60001,
              new PlaceOrder(security, Direction.SELL, n("10"), n("5"))));
      list.add(
          new SequencedEvent(
              2,
              1,
              ++seq,
              seq - 1,
              "buy:" + security,
              2,
              60002,
              new PlaceOrder(security, Direction.BUY, n("11"), n("2"))));
    }
    return list;
  }

  /** 准备状态不改资金，持久化后崩溃可重新执行同一输出而不重复历史。 */
  @Test
  void preparesPersistsThenInstallsAndRetriesIdempotently() {
    var engine = new SecuritiesEngine(catalog);
    var outputs = new OutputStore(source);
    var event = history().get(0);
    var prepared = engine.prepare(event);
    assertEquals(0, engine.sequence());
    outputs.save(event, prepared.output(), false);
    assertEquals(0, engine.sequence());
    assertEquals(1, outputs.position());
    var recovered = new SecuritiesEngine(catalog);
    var retry = recovered.prepare(event);
    outputs.save(event, retry.output(), true);
    recovered.install(retry);
    amount("10000", recovered.account(2).cash().available());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM engine_results", Integer.class));
  }

  /** 输出持久化故障使运行时失败，资金候选未安装，也不能保存领先快照。 */
  @Test
  void outputFailureStopsBeforeBalanceInstallation() {
    var journal = new com.itranswarp.exchange.securities.persistence.EventJournal(source, catalog);
    try (var runtime = new TradingEngineService(source, catalog, 3600000)) {
      runtime.start();
      journal.append(request("pending", 2, new CreditCash(Currency.USD, n("100"))));
      jdbc.execute("RENAME TABLE engine_results TO phase3_output_fault");
      try {
        runtime.poll();
        assertEquals(TradingEngineService.Status.FAILED, runtime.status());
        assertEquals(0, runtime.applied());
        assertThrows(IllegalStateException.class, runtime::save);
      } finally {
        jdbc.execute("RENAME TABLE phase3_output_fault TO engine_results");
      }
    }
  }

  /** 投影五证券同一时间桶独立累加，重复处理不重复 Tick 或 K线成交量。 */
  @Test
  void projectsFiveMarketsWithoutCrossContamination() {
    var engine = new SecuritiesEngine(catalog);
    var outputs = new OutputStore(source);
    var market = new MarketStore(source, catalog);
    for (var event : history()) {
      var prepared = engine.prepare(event);
      outputs.save(event, prepared.output(), true);
      engine.install(prepared);
      market.apply(prepared.output());
      market.apply(prepared.output());
    }
    assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM ticks", Integer.class));
    for (long security = 1001; security <= 1005; security++) {
      amount("2", market.bars(security, "min", 0, Long.MAX_VALUE, 10).get(0).quantity());
      assertEquals(1, market.ticks(security, 10).size());
    }
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM event_outbox WHERE destination='PRIVATE'", Integer.class));
  }

  /** 多笔同事件成交有不同 ordinal，后续价量不能被整条消息去重误删。 */
  @Test
  void keepsEveryFillWithinOneEvent() {
    var engine = new SecuritiesEngine(catalog);
    var history = new History();
    seed(engine, history, 1001);
    apply(engine, history, 3, new PlaceOrder(1001, Direction.SELL, n("10"), n("1")));
    apply(engine, history, 3, new PlaceOrder(1001, Direction.SELL, n("12"), n("2")));
    var output =
        engine
            .prepare(history.add(2, new PlaceOrder(1001, Direction.BUY, n("15"), n("3"))))
            .output();
    assertEquals(
        List.of(0, 1), output.trades().stream().map(ExecutionEnvelope.Trade::tradeIndex).toList());
  }

  /** 旧快照领先输出位置时自动从安全起点补齐，并保持原资金不变。 */
  @Test
  void backfillsPhaseTwoSnapshotAndKeepsCancelledHistory() {
    var journal = new com.itranswarp.exchange.securities.persistence.EventJournal(source, catalog);
    var old = new SecuritiesEngine(catalog);
    var credit = journal.append(request("cash", 2, new CreditCash(Currency.USD, n("100"))));
    old.execute(credit, SecuritiesEngine.Mode.REPLAY);
    new SnapshotRepository(source, catalog).save(old.snapshot());
    assertEquals(0, new OutputStore(source).position());
    try (var runtime = new TradingEngineService(source, catalog, 3600000)) {
      runtime.start();
      assertEquals(1, new OutputStore(source).position());
      amount("100", runtime.account(2).cash().available());
      var buy =
          journal.append(request("buy", 2, new PlaceOrder(1001, Direction.BUY, n("10"), n("2"))));
      runtime.poll();
      journal.append(request("cancel", 2, new CancelOrder(1001, buy.sequenceId())));
      runtime.poll();
      assertEquals(
          "FULLY_CANCELLED",
          jdbc.queryForObject(
              "SELECT status FROM orders WHERE id=?", String.class, buy.sequenceId()));
    }
  }

  /** 制造行情事务中途失败，不能留下部分 Tick 或提前推进消费位置。 */
  @Test
  void rollsBackTickWhenBarWriteFails() {
    var core = new SecuritiesEngine(catalog);
    var last = history();
    var preparedOutputs = new ArrayList<ExecutionEnvelope>();
    for (var event : last) {
      var p = core.prepare(event);
      preparedOutputs.add(p.output());
      core.install(p);
    }
    var market = new MarketStore(source, catalog);
    market.apply(preparedOutputs.get(0));
    market.apply(preparedOutputs.get(1));
    market.apply(preparedOutputs.get(2));
    jdbc.execute("RENAME TABLE day_bars TO phase3_day_bars_fault");
    try {
      assertThrows(RuntimeException.class, () -> market.apply(preparedOutputs.get(3)));
      assertEquals(3, market.position());
      assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ticks", Integer.class));
      assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM min_bars", Integer.class));
    } finally {
      jdbc.execute("RENAME TABLE phase3_day_bars_fault TO day_bars");
    }
  }
}
