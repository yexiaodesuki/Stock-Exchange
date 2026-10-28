/* 内核快照编解码：保持 JSON 字段、排序与版本不变；不持有数据库或公开可变状态。 */
package com.itranswarp.exchange.core;

import static com.itranswarp.exchange.core.EngineState.*;

import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.SecurityCatalog;
import java.util.*;

final class SnapshotCodec {
  private final SecurityCatalog catalog;
  private final String catalogHash;
  private final StateValidator validator;

  /** 使用同一目录指纹与校验器，避免恢复与实时执行采用不同规则。 */
  SnapshotCodec(SecurityCatalog catalog, String catalogHash, StateValidator validator) {
    this.catalog = catalog;
    this.catalogHash = catalogHash;
    this.validator = validator;
  }

  /** 只复制完整事件边界值，保留原字段和排序顺序。 */
  SecuritiesSnapshot encode(EngineState state) {
    var cash =
        state.cash.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                e ->
                    new SecuritiesSnapshot.Cash(
                        e.getKey(), e.getValue().available, e.getValue().frozen))
            .toList();
    var positions =
        state.positions.entrySet().stream()
            .sorted(
                Comparator.<Map.Entry<PositionKey, Balance>>comparingLong(e -> e.getKey().user())
                    .thenComparingLong(e -> e.getKey().security()))
            .map(
                e ->
                    new SecuritiesSnapshot.Position(
                        e.getKey().user(),
                        e.getKey().security(),
                        e.getValue().available,
                        e.getValue().frozen))
            .toList();
    var orders =
        state.orders.values().stream()
            .sorted(Comparator.comparingLong(o -> o.sequence))
            .map(Order::value)
            .toList();
    var markets =
        state.markets.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                e ->
                    new SecuritiesSnapshot.Market(
                        e.getKey(), e.getValue().price, e.getValue().sequence))
            .toList();
    return new SecuritiesSnapshot(
        2, 1, catalogHash, state.sequence, cash, positions, orders, markets);
  }

  /** 在新候选中重建引用并完整校验，失败不影响已发布状态。 */
  EngineState decode(SecuritiesSnapshot snapshot) {
    require(
        snapshot.formatVersion() == 2
            && snapshot.catalogVersion() == 1
            && catalogHash.equals(snapshot.catalogHash()),
        "快照版本或目录指纹不兼容");
    var candidate = new EngineState();
    candidate.sequence = snapshot.sequenceId();
    for (var value : snapshot.markets()) {
      catalog.require(value.securityId());
      var market = new Market();
      market.price = value.lastPrice();
      market.sequence = value.lastSequenceId();
      require(candidate.markets.putIfAbsent(value.securityId(), market) == null, "重复证券盘口");
    }
    require(candidate.markets.size() == catalog.all().size(), "快照缺少证券盘口");
    for (var value : snapshot.cash()) {
      var balance = new Balance();
      balance.available = value.available();
      balance.frozen = value.frozen();
      require(candidate.cash.putIfAbsent(value.userId(), balance) == null, "重复现金账户");
    }
    for (var value : snapshot.positions()) {
      catalog.require(value.securityId());
      var balance = new Balance();
      balance.available = value.available();
      balance.frozen = value.frozen();
      require(
          candidate.positions.putIfAbsent(
                  new PositionKey(value.userId(), value.securityId()), balance)
              == null,
          "重复证券持仓");
    }
    for (var order : snapshot.orders()) candidate.add(new Order(order));
    validator.validate(candidate);
    return candidate;
  }

  /** 非法快照不能被解释为空资产。 */
  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
