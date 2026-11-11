/* 分证券撮合领域服务：保留原价格时间优先、Maker 价格和成交顺序。 */
package com.itranswarp.exchange.match;

import static com.itranswarp.exchange.core.EngineState.number;

import com.itranswarp.exchange.clearing.ClearingService;
import com.itranswarp.exchange.core.*;
import com.itranswarp.exchange.core.EngineState.Order;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.order.OrderService;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.output.ExecutionEnvelope;
import java.math.BigDecimal;
import java.util.*;

public final class MatchEngine {
  private final ClearingService clearing;

  /** 清算组件无状态，所有修改使用同一个候选上下文。 */
  public MatchEngine(ClearingService clearing) {
    this.clearing = clearing;
  }

  /** 接收已经冻结并创建的订单，撮合后更新候选索引和盘口进度。 */
  public ExecutionResult processOrder(EngineState next, SequencedEvent event, Order taker) {
    var market = next.market(taker.security());
    var opposing = taker.direction() == Direction.BUY ? market.sell() : market.buy();
    var trades = new ArrayList<ExecutionResult.Trade>();
    var changed = new ArrayList<ExecutionEnvelope.OrderChange>();
    while (taker.remaining().signum() > 0 && !opposing.isEmpty()) {
      Order maker = opposing.first();
      if (taker.direction() == Direction.BUY
          ? taker.price().compareTo(maker.price()) < 0
          : taker.price().compareTo(maker.price()) > 0) break;
      BigDecimal quantity = taker.remaining().min(maker.remaining());
      Order buyer = taker.direction() == Direction.BUY ? taker : maker;
      Order seller = taker.direction() == Direction.SELL ? taker : maker;
      clearing.clearMatchResult(next, buyer, seller, maker.price(), quantity);
      maker.remaining(number(maker.remaining().subtract(quantity)));
      maker.updated(event.createdAt());
      taker.remaining(number(taker.remaining().subtract(quantity)));
      market.price(maker.price());
      trades.add(
          new ExecutionResult.Trade(
              taker.security(),
              event.sequenceId(),
              maker.id(),
              taker.id(),
              buyer.user(),
              seller.user(),
              maker.price(),
              quantity,
              maker.remaining(),
              taker.remaining()));
      changed.add(
          OrderService.change(
              maker, maker.remaining().signum() == 0 ? "FULLY_FILLED" : "PARTIAL_FILLED"));
      if (maker.remaining().signum() == 0) next.remove(maker);
    }
    if (taker.remaining().signum() > 0) next.add(taker);
    market.sequence(event.sequenceId());
    changed.add(
        OrderService.change(
            taker,
            taker.remaining().signum() == 0
                ? "FULLY_FILLED"
                : taker.remaining().compareTo(taker.quantity()) == 0
                    ? "PENDING"
                    : "PARTIAL_FILLED"));
    return new ExecutionResult(event.sequenceId(), true, "OK", taker.id(), trades, changed);
  }
}
