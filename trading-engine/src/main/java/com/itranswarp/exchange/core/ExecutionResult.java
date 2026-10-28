/* 不可变执行结果；只由实时执行返回，第三阶段再接历史投影和通知输出。 */
package com.itranswarp.exchange.core;

import java.math.BigDecimal;
import java.util.List;

public record ExecutionResult(long sequenceId, boolean accepted, String reason, Long orderId, List<Trade> trades,
        List<com.itranswarp.exchange.securities.output.ExecutionEnvelope.OrderChange> orders) {
    /** 复制成交列表，调用者不能修改核心已经产生的执行结果。 */
    public ExecutionResult { trades = List.copyOf(trades); orders=List.copyOf(orders); }
    /** 保留无订单变化的业务拒绝和入账结果构造入口。 */
    public ExecutionResult(long sequenceId,boolean accepted,String reason,Long orderId,List<Trade> trades) {
        this(sequenceId,accepted,reason,orderId,trades,List.of());
    }
    public record Trade(long securityId, long sequenceId, long makerOrderId, long takerOrderId,
            long buyerId, long sellerId, BigDecimal price, BigDecimal quantity,BigDecimal makerRemaining,BigDecimal takerRemaining) { }
}
