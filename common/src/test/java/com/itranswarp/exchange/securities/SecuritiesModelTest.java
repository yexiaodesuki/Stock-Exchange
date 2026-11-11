/* 证券规则与协议的纯单元测试；不连接原环境，不发送交易或修改资产。 */
package com.itranswarp.exchange.securities;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.util.JsonUtil;
import static org.junit.jupiter.api.Assertions.*;

class SecuritiesModelTest {
    /** 内部提交禁止旧协议、内部负债身份和缺失幂等标识。 */
    @Test void validatesTrustedSubmissionEnvelope() {
        var command=new CreditCash(Currency.USD,BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class,()->new Submission(1,1,"test",2,command));
        assertThrows(IllegalArgumentException.class,()->new Submission(2,1,"test",1,command));
        assertThrows(IllegalArgumentException.class,()->new Submission(2,1,"",2,command));
        var value=new Submission(2,1,"test",2,command);
        assertEquals(value,JsonUtil.readJson(JsonUtil.writeJson(value),Submission.class));
    }

    /** 测试通道与正式通道隔离，正则中的点必须是字面点，不能匹配任意字符。 */
    @Test void restrictsTestChannelNamespace() {
        assertEquals("securities.test.abc.trade",new SecuritiesChannels("securities.test.abc").topic("trade"));
        assertThrows(IllegalArgumentException.class,()->new SecuritiesChannels("securitiesXtestYabc"));
        assertThrows(IllegalArgumentException.class,()->new SecuritiesChannels("SEQUENCE"));
    }
    /** 测试夹具不是生产目录；生产清单只存在于数据库迁移。 */
    private SecurityDefinition definition(long id, String symbol, SecurityStatus status) {
        return new SecurityDefinition(id, symbol, "模拟证券", Currency.USD, new BigDecimal("0.01"), BigDecimal.ONE, status, 1);
    }

    /** 合法整股和分价格通过验证，原始输入不被截断。 */
    @Test void acceptsAlignedValuesWithoutRounding() {
        var catalog = new SecurityCatalog(List.of(definition(1001, "STAR", SecurityStatus.ACTIVE)));
        var command = new PlaceOrder(1001, Direction.BUY, new BigDecimal("25.30"), new BigDecimal("10.00"));
        command.validate(catalog);
        assertEquals(new BigDecimal("25.30"), command.price());
        assertEquals(new BigDecimal("10.00"), command.quantity());
        assertThrows(UnsupportedOperationException.class, () -> catalog.all().clear());
    }

    /** 错误价格/碎股/零值/负值/超范围必须拒绝，不能靠向下取整变成合法委托。 */
    @ParameterizedTest
    @CsvSource({"25.301,10", "25.30,0.1", "0,10", "-1,10", "10,0", "10,-1", "10000000000,1", "10,1000000001", "1E-100000,1", "1E100000,1"})
    void rejectsInvalidOrder(String price, String quantity) {
        var rule = definition(1001, "STAR", SecurityStatus.ACTIVE);
        assertThrows(IllegalArgumentException.class, () -> rule.validateOrder(new BigDecimal(price), new BigDecimal(quantity)));
    }

    /** 未知证券和停牌不能被回退或当作正常交易。 */
    @Test void rejectsUnknownAndSuspendedSecurities() {
        var catalog = new SecurityCatalog(List.of(definition(1001, "STAR", SecurityStatus.SUSPENDED)));
        assertThrows(IllegalArgumentException.class, () -> catalog.require(1002));
        assertThrows(IllegalArgumentException.class, () -> catalog.require(1001).validateOrder(BigDecimal.ONE, BigDecimal.ONE));
    }

    /** 标识或代码冲突、空目录必须快速失败。 */
    @Test void rejectsAmbiguousCatalog() {
        var first = definition(1001, "STAR", SecurityStatus.ACTIVE);
        assertThrows(IllegalArgumentException.class, () -> new SecurityCatalog(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SecurityCatalog(List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () -> new SecurityCatalog(List.of(first, definition(1002, "STAR", SecurityStatus.ACTIVE))));
    }

    /** 不支持的配置步长或版本不能在恢复中偷偷改变规则。 */
    @Test void rejectsUnsupportedDefinition() {
        assertThrows(IllegalArgumentException.class, () -> new SecurityDefinition(1, "ABC", "测试", Currency.USD,
                new BigDecimal("0.001"), BigDecimal.ONE, SecurityStatus.ACTIVE, 1));
        assertThrows(IllegalArgumentException.class, () -> new SecurityDefinition(1, "ABC", "测试", Currency.USD,
                new BigDecimal("0.01"), BigDecimal.ONE, SecurityStatus.ACTIVE, 2));
    }

    /** 各类型 JSON 往返保持十进制和类型，不使用任意 Java 类名反序列化。 */
    @Test void roundTripsEveryCommand() {
        for (Command command : List.of(new PlaceOrder(1001, Direction.BUY, new BigDecimal("25.30"), BigDecimal.TEN),
                new CancelOrder(1001, 2001), new CreditCash(Currency.USD, new BigDecimal("100.00")),
                new CreditPosition(1001, BigDecimal.TEN))) {
            var event = new SequencedEvent(2, 1, 100, 90, "test:100", 1234, 1000, command);
            assertEquals(event, JsonUtil.readJson(JsonUtil.writeJson(event), SequencedEvent.class));
        }
    }

    /** 旧版本、缺失身份、错误前序和非 ASCII 幂等标识都无法进入新事件。 */
    @Test void rejectsInvalidEnvelope() {
        var command = new CancelOrder(1001, 1);
        assertThrows(IllegalArgumentException.class, () -> new SequencedEvent(1, 1, 2, 1, "test", 1, 1, command));
        assertThrows(IllegalArgumentException.class, () -> new SequencedEvent(2, 2, 2, 1, "test", 1, 1, command));
        assertThrows(IllegalArgumentException.class, () -> new SequencedEvent(2, 1, 2, 2, "test", 1, 1, command));
        assertThrows(IllegalArgumentException.class, () -> new SequencedEvent(2, 1, 2, 1, "test", 0, 1, command));
        assertThrows(IllegalArgumentException.class, () -> new SequencedEvent(2, 1, 2, 1, "中文", 1, 1, command));
        assertThrows(RuntimeException.class, () -> JsonUtil.readJson("{\"type\":\"java.lang.Runtime\"}", Command.class));
    }

    /** 不允许碎股配给或小于分的美元入账。 */
    @Test void rejectsInvalidCredits() {
        assertThrows(IllegalArgumentException.class, () -> new CreditCash(Currency.USD, new BigDecimal("0.001")));
        assertThrows(IllegalArgumentException.class, () -> new CreditPosition(1001, new BigDecimal("1.1")));
        assertThrows(IllegalArgumentException.class, () -> new CancelOrder(0, 1));
    }

    /** 命名空间在证券间和旧全局缓存间隔离；消费组不依赖容器 IP。 */
    @Test void isolatesInfrastructureNames() {
        assertNotEquals(SecuritiesProtocol.redisKey(1001, "orderbook"), SecuritiesProtocol.redisKey(1002, "orderbook"));
        assertEquals("securities.v1.trade", SecuritiesProtocol.topic("trade"));
        assertEquals("securities.v1.engine", SecuritiesProtocol.consumerGroup("engine"));
        assertNotEquals(SecuritiesProtocol.marketChannel(1001), SecuritiesProtocol.marketChannel(1002));
        assertThrows(IllegalArgumentException.class, () -> SecuritiesProtocol.topic("BTC"));
        assertThrows(IllegalArgumentException.class, () -> SecuritiesProtocol.redisKey(0, "ticks"));
    }
}
