/* 证券命令和定序协议；仅内部受信入口可提交资金配给，不接入旧 AbstractMessage。 */
package com.itranswarp.exchange.securities;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.itranswarp.exchange.enums.Direction;

public final class SecuritiesProtocol {
    public static final int VERSION = 2;
    public static final int CATALOG_VERSION = 1;
    public static final String NAMESPACE = "securities.v1";

    /** 工具类禁止实例化。 */
    private SecuritiesProtocol() { }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PlaceOrder.class, name = "PLACE_ORDER"),
        @JsonSubTypes.Type(value = CancelOrder.class, name = "CANCEL_ORDER"),
        @JsonSubTypes.Type(value = CreditCash.class, name = "CREDIT_CASH"),
        @JsonSubTypes.Type(value = CreditPosition.class, name = "CREDIT_POSITION")
    })
    public sealed interface Command permits PlaceOrder, CancelOrder, CreditCash, CreditPosition { }

    public record Submission(int protocolVersion, int catalogVersion, String uniqueId, long userId, Command command) {
        /** 内部提交信封；用户身份由未来认证入口填写，禁止普通用户使用内部对手账户。 */
        public Submission {
            if (protocolVersion != VERSION || catalogVersion != CATALOG_VERSION || userId <= 1
                    || uniqueId == null || !uniqueId.matches("[A-Za-z0-9._:-]{1,100}") || command == null)
                throw new IllegalArgumentException("无效的证券内部提交");
        }
    }

    public record PlaceOrder(long securityId, Direction direction, BigDecimal price, BigDecimal quantity) implements Command {
        /** 结构校验；生效的状态与步长还需通过 validate 检查目录。 */
        public PlaceOrder {
            if (securityId <= 0 || direction == null || price == null || quantity == null)
                throw new IllegalArgumentException("证券、方向、价格和股数必填");
        }

        /** 后续接入层和引擎共用目录规则，不信任浏览器输入。 */
        public void validate(SecurityCatalog catalog) { catalog.require(securityId).validateOrder(price, quantity); }
    }

    public record CancelOrder(long securityId, long orderId) implements Command {
        /** 撤单携带证券与订单；用户归属由后续引擎验证。 */
        public CancelOrder {
            if (securityId <= 0 || orderId <= 0) throw new IllegalArgumentException("撤单标识无效");
        }
    }

    public record CreditCash(Currency currency, BigDecimal amount) implements Command {
        /** 受信内部资金事件，不是公开充值接口；唯一事件标识负责防重复。 */
        public CreditCash {
            if (currency != Currency.USD || amount == null || amount.signum() <= 0
                    || amount.precision() > 24 || amount.scale() < -18 || amount.scale() > 18
                    || amount.compareTo(new BigDecimal("9999999999999999.99")) > 0
                    || amount.remainder(new BigDecimal("0.01")).signum() != 0)
                throw new IllegalArgumentException("美元入账金额无效");
        }
    }

    public record CreditPosition(long securityId, BigDecimal quantity) implements Command {
        /** 模拟发行只允许整数股；目录存在性由执行层检查。 */
        public CreditPosition {
            if (securityId <= 0 || quantity == null || quantity.signum() <= 0 || quantity.precision() > 18
                    || quantity.scale() < -18 || quantity.scale() > 18
                    || quantity.compareTo(new BigDecimal("1000000000")) > 0
                    || quantity.remainder(BigDecimal.ONE).signum() != 0)
                throw new IllegalArgumentException("证券入账股数无效");
        }
    }

    public record SequencedEvent(int protocolVersion, int catalogVersion, long sequenceId, long previousId,
            String uniqueId, long userId, long createdAt, Command command) {
        /** 持久事件版本与必填字段校验；连续性以后由执行层检查，不要求序号相差一。 */
        public SequencedEvent {
            if (protocolVersion != VERSION || catalogVersion != CATALOG_VERSION || sequenceId <= 0
                    || previousId < 0 || sequenceId <= previousId || userId <= 0 || createdAt <= 0
                    || uniqueId == null || !uniqueId.matches("[A-Za-z0-9._:-]{1,100}") || command == null)
                throw new IllegalArgumentException("不支持的证券事件版本或无效字段");
        }
    }

    /** Redis 键带版本和证券维度，拒绝未知类型。 */
    public static String redisKey(long securityId, String kind) {
        if (securityId <= 0 || !java.util.Set.of("orderbook", "ticks", "bars:sec", "bars:min", "bars:hour", "bars:day").contains(kind))
            throw new IllegalArgumentException("缓存标识无效");
        return NAMESPACE + ":" + securityId + ":" + kind;
    }

    /** 所有证券共用单分区有序交易流，不按股票拆开资金执行顺序。 */
    public static String topic(String stage) {
        if (!java.util.Set.of("sequence", "transfer", "trade", "tick").contains(stage))
            throw new IllegalArgumentException("Topic 类型无效");
        return NAMESPACE + "." + stage;
    }

    /** 稳定消费组使用应用逻辑名称，不能使用易变的容器 IP。 */
    public static String consumerGroup(String application) {
        if (!java.util.Set.of("sequencer", "engine", "quotation", "api").contains(application))
            throw new IllegalArgumentException("消费组应用无效");
        return NAMESPACE + "." + application;
    }

    /** 行情频道分证券；私人通知按用户路由，避免切换股票遗漏成交通知。 */
    public static String marketChannel(long securityId) {
        if (securityId <= 0) throw new IllegalArgumentException("证券标识无效");
        return NAMESPACE + ":market:" + securityId;
    }
}
