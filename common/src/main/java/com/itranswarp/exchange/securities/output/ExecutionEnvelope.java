/* 可持久化的完整事件输出；公开行情必须剥离用户身份，历史补齐不重发私人通知。 */
package com.itranswarp.exchange.securities.output;

import java.math.BigDecimal;
import java.util.List;
import com.itranswarp.exchange.enums.Direction;

public record ExecutionEnvelope(long sequenceId,long previousId,long userId,String uniqueId,long createdAt,
        boolean accepted,String reason,Long orderId,List<OrderChange> orders,List<Trade> trades,List<Book> books) {
    /** 复制可变集合，持久化与后台发送不引用后续会改变的核心订单。 */
    public ExecutionEnvelope { orders=List.copyOf(orders); trades=List.copyOf(trades); books=List.copyOf(books); }
    public record OrderChange(long id,long sequenceId,long userId,long securityId,Direction direction,
            BigDecimal price,BigDecimal quantity,BigDecimal remaining,String status,long createdAt,long updatedAt) { }
    public record Trade(long securityId,long sequenceId,int tradeIndex,long makerOrderId,long takerOrderId,
            long buyerId,long sellerId,Direction takerDirection,BigDecimal price,BigDecimal quantity,
            BigDecimal makerRemaining,BigDecimal takerRemaining,long createdAt) { }
    public record Level(BigDecimal price,BigDecimal quantity) { }
    public record Book(long securityId,long sequenceId,BigDecimal lastPrice,List<Level> bids,List<Level> asks) {
        /** 盘口只含聚合价量，不能向公开市场泄露订单和用户标识。 */
        public Book { bids=List.copyOf(bids); asks=List.copyOf(asks); }
    }
}
