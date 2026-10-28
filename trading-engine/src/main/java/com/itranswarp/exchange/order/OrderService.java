/* 订单领域服务：规则、冻结、订单创建与撤销；撮合和清算分别委托专用服务。 */
package com.itranswarp.exchange.order;

import static com.itranswarp.exchange.core.EngineState.number;

import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.clearing.ClearingService;
import com.itranswarp.exchange.core.*;
import com.itranswarp.exchange.core.EngineState.Order;
import com.itranswarp.exchange.match.MatchEngine;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.ExecutionEnvelope;
import java.util.*;

public final class OrderService {
  private final SecurityCatalog catalog;
  private final AssetService assets;
  private final MatchEngine matching;
  private final ClearingService clearing;

  /** 每个核心拥有自己的协作对象，但它们不各自持有交易状态。 */
  public OrderService(
      SecurityCatalog catalog,
      AssetService assets,
      MatchEngine matching,
      ClearingService clearing) {
    this.catalog = catalog;
    this.assets = assets;
    this.matching = matching;
    this.clearing = clearing;
  }

  /** 校验顺序、拒绝原因、冻结计算和订单 ID 均与重构前一致。 */
  public ExecutionResult createOrder(EngineState next, SequencedEvent event, PlaceOrder request) {
    try {
      request.validate(catalog);
    } catch (IllegalArgumentException invalid) {
      return reject(event, "INVALID_ORDER");
    }
    if (!assets.tryFreeze(
        next,
        event.userId(),
        request.securityId(),
        request.direction(),
        request.price(),
        request.quantity())) return reject(event, "INSUFFICIENT_BALANCE");
    var taker =
        new Order(
            new SecuritiesSnapshot.Order(
                event.sequenceId(),
                event.sequenceId(),
                event.userId(),
                request.securityId(),
                request.direction(),
                number(request.price()),
                number(request.quantity()),
                number(request.quantity()),
                event.createdAt(),
                event.createdAt()));
    return matching.processOrder(next, event, taker);
  }

  /** 归属与证券均匹配才委托清算解冻，随后同时移除活动订单和盘口索引。 */
  public ExecutionResult cancelOrder(EngineState next, SequencedEvent event, CancelOrder request) {
    Order order = next.order(request.orderId());
    if (order == null || order.user() != event.userId() || order.security() != request.securityId())
      return reject(event, "ORDER_NOT_FOUND");
    clearing.clearCancelOrder(next, order);
    next.remove(order);
    next.market(order.security()).sequence(event.sequenceId());
    order.updated(event.createdAt());
    return new ExecutionResult(
        event.sequenceId(),
        true,
        "OK",
        order.id(),
        List.of(),
        List.of(
            change(
                order,
                order.remaining().compareTo(order.quantity()) == 0
                    ? "FULLY_CANCELLED"
                    : "PARTIAL_CANCELLED")));
  }

  /** 复制关闭前的完整订单字段，保证成交和撤销都能进入历史。 */
  public static ExecutionEnvelope.OrderChange change(Order order, String status) {
    return new ExecutionEnvelope.OrderChange(
        order.id(),
        order.sequence(),
        order.user(),
        order.security(),
        order.direction(),
        order.price(),
        order.quantity(),
        order.remaining(),
        status,
        order.created(),
        order.updated());
  }

  /** 正常拒绝不抛内部异常，仍由核心推进事件位置。 */
  private ExecutionResult reject(SequencedEvent event, String reason) {
    return new ExecutionResult(event.sequenceId(), false, reason, null, List.of());
  }
}
