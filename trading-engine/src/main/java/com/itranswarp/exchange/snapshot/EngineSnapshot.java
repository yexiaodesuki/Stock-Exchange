/* 完整事件边界上的不可变快照：值对象不持有任何仍会被交易线程修改的 Asset 或 OrderEntity。 */
package com.itranswarp.exchange.snapshot;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;

import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.enums.OrderStatus;
import com.itranswarp.exchange.enums.UserType;
import com.itranswarp.exchange.model.trade.OrderEntity;

public record EngineSnapshot(int formatVersion, long sequenceId, String zoneId, BigDecimal marketPrice,
        List<AssetState> assets, List<OrderState> orders) {
    public static final int FORMAT_VERSION = 1;

    /** 防御性复制集合；记录和 BigDecimal 均为不可变值，锁外序列化不受后续交易影响。 */
    public EngineSnapshot {
        if (assets == null || orders == null) {
            throw new IllegalArgumentException("快照缺少资产或订单集合");
        }
        assets = List.copyOf(assets);
        orders = List.copyOf(orders);
    }

    /** 检查结构、唯一标识、活动订单及资金一致性；候选引擎安装前还会独立再次校验。 */
    public void validateShape() {
        if (formatVersion != FORMAT_VERSION || sequenceId < 0 || zoneId == null
                || marketPrice == null || marketPrice.signum() < 0) {
            throw new IllegalArgumentException("快照版本、序号、时区或市场价无效");
        }
        try {
            ZoneId.of(zoneId);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("快照时区格式无效");
        }
        Set<String> assetKeys = new HashSet<>();
        for (AssetState a : assets) {
            if (a.userId <= 0 || a.asset == null || a.available == null || a.frozen == null
                    || a.frozen.signum() < 0 || !assetKeys.add(a.userId + ":" + a.asset)) {
                throw new IllegalArgumentException("快照资产字段无效或重复");
            }
        }
        Set<Long> ids = new HashSet<>();
        Set<Long> sequences = new HashSet<>();
        for (OrderState o : orders) {
            if (o.id <= 0 || o.userId <= 0 || o.sequenceId <= 0 || o.sequenceId > sequenceId
                    || o.direction == null || o.status == null || o.status.isFinalStatus
                    || o.price == null || o.price.signum() <= 0
                    || o.quantity == null || o.quantity.signum() <= 0
                    || o.unfilledQuantity == null || o.unfilledQuantity.signum() <= 0
                    || o.unfilledQuantity.compareTo(o.quantity) > 0
                    || o.createdAt <= 0 || o.updatedAt < o.createdAt
                    || !ids.add(o.id) || !sequences.add(o.sequenceId)) {
                throw new IllegalArgumentException("快照活动订单字段无效或重复");
            }
            boolean unfilled = o.unfilledQuantity.compareTo(o.quantity) == 0;
            if ((o.status == OrderStatus.PENDING) != unfilled) {
                throw new IllegalArgumentException("快照订单状态与未成交数量不一致");
            }
        }
        if (sequenceId == 0 && (!assets.isEmpty() || !orders.isEmpty() || marketPrice.signum() != 0)) {
            throw new IllegalArgumentException("零序号快照不能包含交易状态");
        }
        validateBalances();
    }

    /** 直接对值对象验证余额守恒、负债账户规则、冻结对应关系和不交叉盘口。 */
    private void validateBalances() {
        Map<String, BigDecimal> required = new HashMap<>();
        BigDecimal highestBuy = null;
        BigDecimal lowestSell = null;
        for (OrderState order : orders) {
            AssetEnum asset = order.direction == Direction.BUY ? AssetEnum.USD : AssetEnum.BTC;
            BigDecimal frozen = order.direction == Direction.BUY
                    ? order.price.multiply(order.unfilledQuantity) : order.unfilledQuantity;
            required.merge(order.userId + ":" + asset, frozen, BigDecimal::add);
            if (order.direction == Direction.BUY) {
                highestBuy = highestBuy == null ? order.price : highestBuy.max(order.price);
            } else {
                lowestSell = lowestSell == null ? order.price : lowestSell.min(order.price);
            }
        }
        if (highestBuy != null && lowestSell != null && highestBuy.compareTo(lowestSell) >= 0) {
            throw new IllegalArgumentException("快照买卖盘口交叉");
        }
        Map<AssetEnum, BigDecimal> totals = new EnumMap<>(AssetEnum.class);
        for (AssetState balance : assets) {
            boolean debt = balance.userId == UserType.DEBT.getInternalUserId();
            if ((debt && (balance.available.signum() > 0 || balance.frozen.signum() != 0))
                    || (!debt && balance.available.signum() < 0)) {
                throw new IllegalArgumentException("快照账户余额违反约束");
            }
            BigDecimal expected = required.remove(balance.userId + ":" + balance.asset);
            if (balance.frozen.compareTo(expected == null ? BigDecimal.ZERO : expected) != 0) {
                throw new IllegalArgumentException("快照冻结余额与活动订单不一致");
            }
            totals.merge(balance.asset, balance.available.add(balance.frozen), BigDecimal::add);
        }
        if (!required.isEmpty() || totals.values().stream().anyMatch(total -> total.signum() != 0)) {
            throw new IllegalArgumentException("快照缺少冻结账户或资产总额不守恒");
        }
    }

    /** 每个账户币种的完整可用额和冻结额，包括内部负债账户。 */
    public record AssetState(long userId, AssetEnum asset, BigDecimal available, BigDecimal frozen) { }

    /** 活动订单的完整业务状态；不保存仅用于并发复制检查的瞬时 version 计数器。 */
    public record OrderState(long id, long sequenceId, long userId, Direction direction, OrderStatus status,
            BigDecimal price, BigDecimal quantity, BigDecimal unfilledQuantity, long createdAt, long updatedAt) {
        /** 在引擎锁内复制字段为不可变值，不保留原订单引用。 */
        public static OrderState from(OrderEntity order) {
            return new OrderState(order.id, order.sequenceId, order.userId, order.direction, order.status,
                    order.price, order.quantity, order.unfilledQuantity, order.createdAt, order.updatedAt);
        }

        /** 生成一个新订单对象，用于同时重建全局索引、用户索引和订单簿；不冻结资产。 */
        public OrderEntity toOrder() {
            OrderEntity order = new OrderEntity();
            order.id = id;
            order.sequenceId = sequenceId;
            order.userId = userId;
            order.direction = direction;
            order.status = status;
            order.price = price;
            order.quantity = quantity;
            order.unfilledQuantity = unfilledQuantity;
            order.createdAt = createdAt;
            order.updatedAt = updatedAt;
            return order;
        }
    }
}
