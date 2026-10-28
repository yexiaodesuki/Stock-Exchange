/* 格式 2 多证券完整快照；只保存不可变值，不保存输出队列或 Kafka 消费位置。 */
package com.itranswarp.exchange.core;

import java.math.BigDecimal;
import java.util.List;
import com.itranswarp.exchange.enums.Direction;

public record SecuritiesSnapshot(int formatVersion, int catalogVersion, String catalogHash, long sequenceId,
        List<Cash> cash, List<Position> positions, List<Order> orders, List<Market> markets) {
    /** 防御性复制集合；空字段和非法版本由候选引擎的 restore 严格校验。 */
    public SecuritiesSnapshot {
        cash = List.copyOf(cash); positions = List.copyOf(positions);
        orders = List.copyOf(orders); markets = List.copyOf(markets);
    }
    public record Cash(long userId, BigDecimal available, BigDecimal frozen) { }
    public record Position(long userId, long securityId, BigDecimal available, BigDecimal frozen) { }
    public record Order(long id, long sequenceId, long userId, long securityId, Direction direction,
            BigDecimal price, BigDecimal quantity, BigDecimal remaining, long createdAt, long updatedAt) { }
    public record Market(long securityId, BigDecimal lastPrice, long lastSequenceId) { }
}
