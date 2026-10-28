/* 单事件候选状态：现金共享、持仓分证券、盘口引用全局订单中的同一对象。 */
package com.itranswarp.exchange.core;

import com.itranswarp.exchange.enums.Direction;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

public final class EngineState {
  /** 候选只能由核心创建；不会通过查询接口返回给外部。 */
  EngineState() {}

  /** 按标识读取候选订单，不公开整个可变索引。 */
  public Order order(long id) {
    return orders.get(id);
  }

  /** 按证券读取本次候选盘口。 */
  public Market market(long id) {
    return markets.get(id);
  }

  long sequence;
  final Map<Long, Balance> cash = new HashMap<>();
  final Map<PositionKey, Balance> positions = new HashMap<>();
  final Map<Long, Order> orders = new HashMap<>();
  final Map<Long, Market> markets = new HashMap<>();

  /** 深复制资金、订单和盘口，任何候选执行失败都不能污染已发布状态。 */
  EngineState copy() {
    var copy = new EngineState();
    copy.sequence = sequence;
    cash.forEach((id, balance) -> copy.cash.put(id, balance.copy()));
    positions.forEach((key, balance) -> copy.positions.put(key, balance.copy()));
    markets.forEach(
        (id, market) -> {
          var m = new Market();
          m.price = market.price;
          m.sequence = market.sequence;
          copy.markets.put(id, m);
        });
    orders.values().forEach(order -> copy.add(order.copy()));
    return copy;
  }

  /** 获取共享 USD 账户；候选状态中新用户从零开始，不虚构资金。 */
  public Balance cash(long user) {
    return cash.computeIfAbsent(user, ignored -> new Balance());
  }

  /** 获取特定证券持仓，不会用另一只股票的持仓抵扣。 */
  public Balance position(long user, long security) {
    return positions.computeIfAbsent(new PositionKey(user, security), ignored -> new Balance());
  }

  /** 同一个订单同时进入全局索引和所属盘口；重复标识视为内部错误。 */
  public void add(Order order) {
    if (orders.putIfAbsent(order.id, order) != null || !marketBook(order).add(order))
      throw new IllegalStateException("活动订单重复");
  }

  /** 从两个索引同时移除；漏项必须暴露，不能静默留下冻结。 */
  public void remove(Order order) {
    if (orders.remove(order.id) != order || !marketBook(order).remove(order))
      throw new IllegalStateException("活动订单索引不一致");
  }

  /** 依据证券与方向取得盘口；证券必须事先由目录建立。 */
  TreeSet<Order> marketBook(Order order) {
    var market = markets.get(order.security);
    if (market == null) throw new IllegalStateException("订单证券没有盘口");
    return order.direction == Direction.BUY ? market.buy : market.sell;
  }

  /** 十进制规范化，保证相同业务历史产生一致快照文本。 */
  public static BigDecimal number(BigDecimal value) {
    return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
  }

  record PositionKey(long user, long security) {}

  public static final class Balance {
    BigDecimal available = BigDecimal.ZERO, frozen = BigDecimal.ZERO;

    /** 读取可用金额或股数；BigDecimal 不可变。 */
    public BigDecimal available() {
      return available;
    }

    /** 读取当前候选冻结额。 */
    public BigDecimal frozen() {
      return frozen;
    }

    /** 深复制余额容器；BigDecimal 本身不可变。 */
    Balance copy() {
      var copy = new Balance();
      copy.available = available;
      copy.frozen = frozen;
      return copy;
    }

    /** 修改可用额，禁止浮点计算。 */
    public void available(BigDecimal delta) {
      available = number(available.add(delta));
    }

    /** 修改冻结额，完整事件结束后还要验证冻结与订单对应关系。 */
    public void frozen(BigDecimal delta) {
      frozen = number(frozen.add(delta));
    }
  }

  public static final class Market {
    /** 取得本证券买盘，仅供当前事件的订单与撮合服务使用。 */
    public TreeSet<Order> buy() {
      return buy;
    }

    /** 取得本证券卖盘，不能与其他证券共用。 */
    public TreeSet<Order> sell() {
      return sell;
    }

    /** 更新候选最新成交价，安装候选之前不对外可见。 */
    public void price(BigDecimal value) {
      price = value;
    }

    /** 推进本证券盘口版本，包含单独撤单。 */
    public void sequence(long value) {
      sequence = value;
    }

    BigDecimal price = BigDecimal.ZERO;
    long sequence;
    final TreeSet<Order> buy =
        new TreeSet<>(
            Comparator.<Order, BigDecimal>comparing(o -> o.price)
                .reversed()
                .thenComparingLong(o -> o.sequence));
    final TreeSet<Order> sell =
        new TreeSet<>(
            Comparator.<Order, BigDecimal>comparing(o -> o.price)
                .thenComparingLong(o -> o.sequence));
  }

  public static final class Order {
    final long id, sequence, user, security, created;
    final Direction direction;
    final BigDecimal price, quantity;
    BigDecimal remaining;
    long updated;

    /** 读取候选订单的 id 字段，不创建另一份订单索引。 */
    public long id() {
      return id;
    }

    /** 读取候选订单的 sequence 字段，不创建另一份订单索引。 */
    public long sequence() {
      return sequence;
    }

    /** 读取候选订单的 user 字段，不创建另一份订单索引。 */
    public long user() {
      return user;
    }

    /** 读取候选订单的 security 字段，不创建另一份订单索引。 */
    public long security() {
      return security;
    }

    /** 读取候选订单的 created 字段，不创建另一份订单索引。 */
    public long created() {
      return created;
    }

    /** 读取候选订单的 direction 字段，不创建另一份订单索引。 */
    public Direction direction() {
      return direction;
    }

    /** 读取候选订单的 price 字段，不创建另一份订单索引。 */
    public BigDecimal price() {
      return price;
    }

    /** 读取候选订单的 quantity 字段，不创建另一份订单索引。 */
    public BigDecimal quantity() {
      return quantity;
    }

    /** 读取候选订单的 remaining 字段，不创建另一份订单索引。 */
    public BigDecimal remaining() {
      return remaining;
    }

    /** 读取候选订单的 updated 字段，不创建另一份订单索引。 */
    public long updated() {
      return updated;
    }

    /** 成交只修改候选剩余量，完整事件末尾统一校验。 */
    public void remaining(BigDecimal value) {
      remaining = value;
    }

    /** 更新时间来自事件，不使用服务本机时钟。 */
    public void updated(long value) {
      updated = value;
    }

    /** 从快照值建立订单；不冻结资产、不重新撮合。 */
    public Order(SecuritiesSnapshot.Order value) {
      id = value.id();
      sequence = value.sequenceId();
      user = value.userId();
      security = value.securityId();
      direction = value.direction();
      price = value.price();
      quantity = value.quantity();
      remaining = value.remaining();
      created = value.createdAt();
      updated = value.updatedAt();
    }

    /** 复制为不可变快照/查询值，不向调用者暴露可变订单。 */
    SecuritiesSnapshot.Order value() {
      return new SecuritiesSnapshot.Order(
          id, sequence, user, security, direction, price, quantity, remaining, created, updated);
    }

    /** 候选事件使用新订单对象，失败时可整体丢弃。 */
    Order copy() {
      return new Order(value());
    }
  }
}
