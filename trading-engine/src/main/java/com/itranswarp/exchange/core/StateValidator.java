/* 候选状态不变量校验：集中检查共享现金、分证券持仓、冻结、订单与盘口引用。 */
package com.itranswarp.exchange.core;

import static com.itranswarp.exchange.core.EngineState.*;

import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.SecurityCatalog;
import java.math.BigDecimal;
import java.util.*;

final class StateValidator {
  private final SecurityCatalog catalog;
  private final long DEBT;

  /** 校验器只读取候选，不提交余额或产生输出。 */
  StateValidator(SecurityCatalog catalog, long debt) {
    this.catalog = catalog;
    this.DEBT = debt;
  }

  /** 保持重构前全部检查顺序和异常语义，失败阻止候选安装。 */
  void validate(EngineState candidate) {
    require(candidate.sequence >= 0, "事件位置无效");
    var cashFrozen = new HashMap<Long, BigDecimal>();
    var positionsFrozen = new HashMap<PositionKey, BigDecimal>();
    var ids = new HashSet<Long>();
    int bookSize = 0;
    for (var entry : candidate.markets.entrySet()) {
      catalog.require(entry.getKey());
      var market = entry.getValue();
      require(
          market.price != null
              && market.price.signum() >= 0
              && market.price.remainder(new BigDecimal("0.01")).signum() == 0
              && market.sequence >= 0
              && market.sequence <= candidate.sequence,
          "盘口价格或位置无效");
      require(
          market.buy.isEmpty()
              || market.sell.isEmpty()
              || market.buy.first().price.compareTo(market.sell.first().price) < 0,
          "盘口交叉");
      for (var book : List.of(market.buy, market.sell))
        for (var order : book) {
          bookSize++;
          require(
              order.security == entry.getKey() && candidate.orders.get(order.id) == order,
              "盘口订单引用不一致");
          require((book == market.buy) == (order.direction == Direction.BUY), "订单方向不一致");
        }
    }
    require(bookSize == candidate.orders.size(), "订单索引数量不一致");
    for (var order : candidate.orders.values()) {
      require(
          order.user > DEBT
              && order.id == order.sequence
              && order.sequence > 0
              && order.sequence <= candidate.sequence
              && ids.add(order.id),
          "订单标识无效");
      require(candidate.markets.get(order.security).sequence >= order.sequence, "盘口进度落后于活动订单");
      require(
          order.direction != null
              && order.price != null
              && order.price.signum() > 0
              && order.price.remainder(new BigDecimal("0.01")).signum() == 0
              && order.price.compareTo(new BigDecimal("9999999999.99")) <= 0,
          "订单价格无效");
      require(
          order.quantity != null
              && order.quantity.signum() > 0
              && order.quantity.remainder(BigDecimal.ONE).signum() == 0
              && order.quantity.compareTo(new BigDecimal("1000000000")) <= 0
              && order.remaining != null
              && order.remaining.signum() > 0
              && order.remaining.compareTo(order.quantity) <= 0
              && order.remaining.remainder(BigDecimal.ONE).signum() == 0
              && order.created > 0
              && order.updated >= order.created,
          "订单数量或时间无效");
      if (order.direction == Direction.BUY)
        cashFrozen.merge(order.user, order.price.multiply(order.remaining), BigDecimal::add);
      else
        positionsFrozen.merge(
            new PositionKey(order.user, order.security), order.remaining, BigDecimal::add);
    }
    BigDecimal totalCash = BigDecimal.ZERO;
    for (var entry : candidate.cash.entrySet()) {
      validateBalance(entry.getKey(), entry.getValue(), false);
      require(
          entry
                  .getValue()
                  .frozen
                  .compareTo(cashFrozen.getOrDefault(entry.getKey(), BigDecimal.ZERO))
              == 0,
          "现金冻结与全部证券买单不一致");
      cashFrozen.remove(entry.getKey());
      totalCash = totalCash.add(entry.getValue().available).add(entry.getValue().frozen);
    }
    require(cashFrozen.isEmpty() && totalCash.signum() == 0, "资金不守恒或缺少冻结账户");
    var totals = new HashMap<Long, BigDecimal>();
    for (var entry : candidate.positions.entrySet()) {
      validateBalance(entry.getKey().user(), entry.getValue(), true);
      require(
          entry
                  .getValue()
                  .frozen
                  .compareTo(positionsFrozen.getOrDefault(entry.getKey(), BigDecimal.ZERO))
              == 0,
          "持仓冻结与本证券卖单不一致");
      positionsFrozen.remove(entry.getKey());
      totals.merge(
          entry.getKey().security(),
          entry.getValue().available.add(entry.getValue().frozen),
          BigDecimal::add);
    }
    require(
        positionsFrozen.isEmpty() && totals.values().stream().allMatch(v -> v.signum() == 0),
        "证券不守恒或缺少冻结持仓");
    require(
        candidate.sequence != 0
            || (candidate.cash.isEmpty()
                && candidate.positions.isEmpty()
                && candidate.orders.isEmpty()
                && candidate.markets.values().stream()
                    .allMatch(m -> m.price.signum() == 0 && m.sequence == 0)),
        "零序号快照不能包含交易状态");
  }

  /** 现金与持仓精度不同，内部负债账户保持原特殊约束。 */
  private void validateBalance(long user, Balance balance, boolean position) {
    require(
        user > 0
            && balance.available != null
            && balance.frozen != null
            && balance.frozen.signum() >= 0,
        "余额字段无效");
    require(
        user == DEBT
            ? balance.available.signum() <= 0 && balance.frozen.signum() == 0
            : balance.available.signum() >= 0,
        "账户余额越界");
    BigDecimal step = position ? BigDecimal.ONE : new BigDecimal("0.01");
    require(
        balance.available.remainder(step).signum() == 0
            && balance.frozen.remainder(step).signum() == 0,
        "余额精度无效");
  }

  /** 校验错误继续传播，不能修补为可交易状态。 */
  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
