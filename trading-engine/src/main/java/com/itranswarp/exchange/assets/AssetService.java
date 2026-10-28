/* 资产领域服务：只修改传入的事件候选，不持有第二份全局余额。 */
package com.itranswarp.exchange.assets;

import com.itranswarp.exchange.core.EngineState;
import com.itranswarp.exchange.enums.Direction;
import java.math.BigDecimal;

public final class AssetService {
  private final long debt;

  /** 内部负债账户由核心固定传入，普通 API 无法指定。 */
  public AssetService(long debt) {
    this.debt = debt;
  }

  /** 配给现金同时记内部对手账，保持原守恒规则。 */
  public void creditCash(EngineState next, long user, BigDecimal amount) {
    next.cash(user).available(amount);
    next.cash(debt).available(amount.negate());
  }

  /** 配给同一证券持仓，目录校验由事件分发层先完成。 */
  public void creditPosition(EngineState next, long user, long security, BigDecimal amount) {
    next.position(user, security).available(amount);
    next.position(debt, security).available(amount.negate());
  }

  /** 买单冻结限价金额，卖单冻结对应股数；不足返回 false，不安装候选。 */
  public boolean tryFreeze(
      EngineState next,
      long user,
      long security,
      Direction direction,
      BigDecimal price,
      BigDecimal quantity) {
    var balance = direction == Direction.BUY ? next.cash(user) : next.position(user, security);
    BigDecimal reserved = direction == Direction.BUY ? price.multiply(quantity) : quantity;
    if (balance.available().compareTo(reserved) < 0) return false;
    balance.available(reserved.negate());
    balance.frozen(reserved);
    return true;
  }
}
