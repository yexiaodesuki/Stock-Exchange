/* 清算领域服务：处理成交价差与撤单解冻，不发送消息、不提交数据库。 */
package com.itranswarp.exchange.clearing;

import com.itranswarp.exchange.core.EngineState;
import com.itranswarp.exchange.core.EngineState.Order;
import com.itranswarp.exchange.enums.Direction;
import java.math.BigDecimal;

public final class ClearingService {
  /** 按 Maker 价结算并退回买单限价差；仍在同一候选事件内。 */
  public void clearMatchResult(
      EngineState next, Order buyer, Order seller, BigDecimal price, BigDecimal quantity) {
    require(buyer.security() == seller.security(), "跨证券清算");
    BigDecimal reserved = buyer.price().multiply(quantity), paid = price.multiply(quantity);
    var cash = next.cash(buyer.user());
    var position = next.position(seller.user(), seller.security());
    require(
        cash.frozen().compareTo(reserved) >= 0 && position.frozen().compareTo(quantity) >= 0,
        "清算冻结不足");
    cash.frozen(reserved.negate());
    cash.available(reserved.subtract(paid));
    next.cash(seller.user()).available(paid);
    position.frozen(quantity.negate());
    next.position(buyer.user(), buyer.security()).available(quantity);
  }

  /** 撤单仅退回剩余委托冻结，已成交部分不回滚。 */
  public void clearCancelOrder(EngineState next, Order order) {
    var balance =
        order.direction() == Direction.BUY
            ? next.cash(order.user())
            : next.position(order.user(), order.security());
    BigDecimal amount =
        order.direction() == Direction.BUY
            ? order.price().multiply(order.remaining())
            : order.remaining();
    require(balance.frozen().compareTo(amount) >= 0, "撤单冻结不足");
    balance.frozen(amount.negate());
    balance.available(amount);
  }

  /** 不变量错误继续向核心传播，禁止带半状态继续交易。 */
  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
