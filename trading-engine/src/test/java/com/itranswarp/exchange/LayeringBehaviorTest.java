/* 分层重构特征测试：固定输入下逐事件输出、五证券盘口和完整快照必须与重构前基准一致。 */
package com.itranswarp.exchange;

import static com.itranswarp.exchange.SecuritiesCoreTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.itranswarp.exchange.core.*;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.Currency;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.util.JsonUtil;
import java.util.*;
import org.junit.jupiter.api.Test;

class LayeringBehaviorTest {
  /** 固定五证券混合历史，涵盖价差、部分/完全成交、撤单、拒绝和多个用户的共享现金。 */
  @Test
  void exactEventOutputsAndSnapshotsRemainUnchanged() {
    var engine = new SecuritiesEngine(catalog());
    var history = new History();
    var commands = new ArrayList<Map.Entry<Long, Command>>();
    commands.add(Map.entry(2L, new CreditCash(Currency.USD, n("10000"))));
    commands.add(Map.entry(3L, new CreditCash(Currency.USD, n("2000"))));
    for (long security = 1001; security <= 1005; security++) {
      commands.add(Map.entry(3L, new CreditPosition(security, n("100"))));
      commands.add(Map.entry(3L, new PlaceOrder(security, Direction.SELL, n("100"), n("10"))));
      commands.add(Map.entry(2L, new PlaceOrder(security, Direction.BUY, n("105"), n("4"))));
      commands.add(Map.entry(2L, new PlaceOrder(security, Direction.BUY, n("100"), n("6"))));
      long cancelId = commands.size() + 1;
      commands.add(Map.entry(2L, new PlaceOrder(security, Direction.BUY, n("90"), n("2"))));
      commands.add(Map.entry(3L, new CancelOrder(security, cancelId)));
      commands.add(Map.entry(2L, new CancelOrder(security, cancelId)));
      commands.add(Map.entry(4L, new PlaceOrder(security, Direction.SELL, n("100"), n("1"))));
    }
    var transcript = new StringBuilder();
    for (var entry : commands) {
      var event = history.add(entry.getKey(), entry.getValue());
      var prepared = engine.prepare(event);
      transcript.append(JsonUtil.writeJson(prepared.output()));
      engine.install(prepared);
      transcript.append(JsonUtil.writeJson(engine.snapshot()));
      for (long security = 1001; security <= 1005; security++)
        transcript.append(JsonUtil.writeJson(engine.book(security)));
    }
    String fingerprint = SecuritiesEngine.checksum(transcript.toString());
    assertEquals(
        "cbcd44c2ad9f32b4e95952c22c0b34f5b6633134add4c6c03a06978d78f5e9b1",
        fingerprint,
        "逐事件输出、盘口或快照与重构前不一致");
    var replay = new SecuritiesEngine(catalog());
    for (var event : history.events)
      assertTrue(replay.execute(event, SecuritiesEngine.Mode.REPLAY).isEmpty());
    assertEquals(JsonUtil.writeJson(engine.snapshot()), JsonUtil.writeJson(replay.snapshot()));
    assertTrue(
        engine
            .execute(history.events.get(history.events.size() - 1), SecuritiesEngine.Mode.LIVE)
            .isEmpty());
  }
}
