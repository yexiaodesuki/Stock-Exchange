/* 证券核心单元/恢复测试：不连接容器，不发送消息，验证完整状态而非仅接口成功。 */
package com.itranswarp.exchange;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.*;
import com.itranswarp.exchange.securities.Currency;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.util.JsonUtil;
import com.itranswarp.exchange.core.*;
import static org.junit.jupiter.api.Assertions.*;

class SecuritiesCoreTest {
    /** 测试规则夹具；实际生产目录始终从 SQL 加载。 */
    static SecurityCatalog catalog() {
        var entries=new ArrayList<SecurityDefinition>();
        for(int index=0;index<5;index++) entries.add(new SecurityDefinition(1001+index,"TEST"+index,"模拟",Currency.USD,
                new BigDecimal("0.01"),BigDecimal.ONE,SecurityStatus.ACTIVE,1));
        return new SecurityCatalog(entries);
    }
    /** 构造单调时钟的有序测试历史，避免依赖系统时间或随机订单标识。 */
    static final class History {
        final List<SequencedEvent> events=new ArrayList<>();
        /** 为下一命令分配唯一序号与前序，用户 2/3 是普通测试用户。 */
        SequencedEvent add(long user,Command command) {
            long sequence=events.size()+1;
            var event=new SequencedEvent(2,1,sequence,sequence-1,"unit:"+sequence,user,1000+sequence,command); events.add(event); return event;
        }
    }
    /** 十进制测试值不得经 double 中转。 */
    static BigDecimal n(String value) { return new BigDecimal(value); }
    /** 执行并取得实时结果，重放模式在单独用例验证。 */
    static ExecutionResult apply(SecuritiesEngine engine,History history,long user,Command command) {
        return engine.execute(history.add(user,command),SecuritiesEngine.Mode.LIVE).orElseThrow();
    }
    /** 为两方准备明确的模拟资金和持仓，不依赖页面注册随机充值。 */
    static void seed(SecuritiesEngine engine,History history,long security) {
        apply(engine,history,2,new CreditCash(Currency.USD,n("10000")));
        apply(engine,history,3,new CreditPosition(security,n("100")));
    }
    /** 不以 BigDecimal scale 差异误报数值差异。 */
    static void amount(String expected,BigDecimal actual) { assertEquals(0,n(expected).compareTo(actual)); }
    /** 查询某用户特定股票，不用列表位置假定股票标识。 */
    static SecuritiesSnapshot.Position position(SecuritiesEngine engine,long user,long security) {
        return engine.account(user).positions().stream().filter(p->p.securityId()==security).findFirst().orElseThrow();
    }

    /** 五证券分别成交都应按 Maker 价格付款并释放价差，其他持仓不改变。 */
    @ParameterizedTest @ValueSource(longs={1001,1002,1003,1004,1005})
    void settlesEachSecurityAtMakerPrice(long security) {
        var engine=new SecuritiesEngine(catalog()); var history=new History(); seed(engine,history,security);
        apply(engine,history,3,new PlaceOrder(security,Direction.SELL,n("100"),n("10")));
        var result=apply(engine,history,2,new PlaceOrder(security,Direction.BUY,n("105"),n("10")));
        amount("100",result.trades().get(0).price()); amount("9000",engine.account(2).cash().available());
        amount("0",engine.account(2).cash().frozen()); amount("1000",engine.account(3).cash().available());
        amount("10",position(engine,2,security).available()); amount("90",position(engine,3,security).available());
        assertTrue(engine.snapshot().orders().isEmpty());
        engine.account(2).positions().stream().filter(p->p.securityId()!=security).forEach(p->amount("0",p.available()));
    }

    /** 卖方主动吃买盘时也按 Maker 买价成交，不能错误按卖方更低报价结算。 */
    @ParameterizedTest @ValueSource(longs={1001,1002,1003,1004,1005})
    void aggressiveSellerReceivesRestingBuyPrice(long security) {
        var engine=new SecuritiesEngine(catalog()); var history=new History(); seed(engine,history,security);
        apply(engine,history,2,new PlaceOrder(security,Direction.BUY,n("105"),n("10")));
        var result=apply(engine,history,3,new PlaceOrder(security,Direction.SELL,n("100"),n("10")));
        amount("105",result.trades().get(0).price()); amount("8950",engine.account(2).cash().available());
        amount("1050",engine.account(3).cash().available()); amount("0",engine.account(2).cash().frozen());
        assertTrue(engine.snapshot().orders().isEmpty());
    }

    /** 固定随机种子构造混合五证券历史，逐事件比较运行状态与反复恢复状态。 */
    @Test void mixedMarketsRemainEquivalentThroughRepeatedSnapshots() {
        var uninterrupted=new SecuritiesEngine(catalog()); var recovered=new SecuritiesEngine(catalog()); var history=new History();
        for(long user:List.of(2L,3L)) {
            var cash=history.add(user,new CreditCash(Currency.USD,n("10000")));
            uninterrupted.execute(cash,SecuritiesEngine.Mode.REPLAY); recovered.execute(cash,SecuritiesEngine.Mode.REPLAY);
            for(long security=1001;security<=1005;security++) {
                var credit=history.add(user,new CreditPosition(security,n("100")));
                uninterrupted.execute(credit,SecuritiesEngine.Mode.REPLAY); recovered.execute(credit,SecuritiesEngine.Mode.REPLAY);
            }
        }
        var random=new Random(20261027);
        for(int index=0;index<200;index++) {
            Command command; long user=2+random.nextInt(2);
            var orders=uninterrupted.snapshot().orders();
            if(index%5==0 && !orders.isEmpty()) { var order=orders.get(random.nextInt(orders.size())); user=order.userId(); command=new CancelOrder(order.securityId(),order.id()); }
            else command=new PlaceOrder(1001+random.nextInt(5),random.nextBoolean()?Direction.BUY:Direction.SELL,
                    n(Integer.toString(18+random.nextInt(5))),n(Integer.toString(1+random.nextInt(4))));
            var event=history.add(user,command); uninterrupted.execute(event,SecuritiesEngine.Mode.LIVE); recovered.execute(event,SecuritiesEngine.Mode.REPLAY);
            assertEquals(uninterrupted.snapshot(),recovered.snapshot(),"混合事件 "+index);
            if(index%7==0) { var next=new SecuritiesEngine(catalog()); next.restore(recovered.snapshot()); recovered=next; }
        }
    }

    /** 五证券共用现金，第二只股票不能重复花费已经被第一只冻结的资金。 */
    @Test void sharesCashButNeverCrossMatches() {
        var engine=new SecuritiesEngine(catalog()); var history=new History();
        apply(engine,history,2,new CreditCash(Currency.USD,n("1000")));
        apply(engine,history,3,new CreditPosition(1002,n("100")));
        apply(engine,history,2,new PlaceOrder(1001,Direction.BUY,n("100"),n("6")));
        var rejected=apply(engine,history,2,new PlaceOrder(1002,Direction.BUY,n("100"),n("6")));
        assertFalse(rejected.accepted()); amount("400",engine.account(2).cash().available()); amount("600",engine.account(2).cash().frozen());
        var sell=apply(engine,history,3,new PlaceOrder(1002,Direction.SELL,n("90"),n("6")));
        assertTrue(sell.trades().isEmpty()); assertEquals(2,engine.snapshot().orders().size());
    }

    /** 部分成交只退本次价差，撤单只释放剩余委托冻结。 */
    @Test void partialFillAndCancelReleaseExactRemainder() {
        var engine=new SecuritiesEngine(catalog()); var history=new History(); seed(engine,history,1001);
        apply(engine,history,3,new PlaceOrder(1001,Direction.SELL,n("100"),n("4")));
        long buy=apply(engine,history,2,new PlaceOrder(1001,Direction.BUY,n("105"),n("10"))).orderId();
        amount("8970",engine.account(2).cash().available()); amount("630",engine.account(2).cash().frozen());
        var before=engine.account(2);
        assertFalse(apply(engine,history,3,new CancelOrder(1001,buy)).accepted());
        assertFalse(apply(engine,history,2,new CancelOrder(1002,buy)).accepted()); assertEquals(before,engine.account(2));
        assertTrue(apply(engine,history,2,new CancelOrder(1001,buy)).accepted());
        amount("9600",engine.account(2).cash().available()); amount("0",engine.account(2).cash().frozen());
        amount("4",position(engine,2,1001).available()); assertTrue(engine.snapshot().orders().isEmpty());
    }

    /** 多 Maker 成交顺序由价格优先和全局序号确定，恢复不能改变同价优先级。 */
    @Test void preservesPriceTimePriorityAcrossRestore() {
        var engine=new SecuritiesEngine(catalog()); var history=new History(); seed(engine,history,1001);
        long first=apply(engine,history,3,new PlaceOrder(1001,Direction.SELL,n("100"),n("2"))).orderId();
        long second=apply(engine,history,3,new PlaceOrder(1001,Direction.SELL,n("100"),n("2"))).orderId();
        long cheaper=apply(engine,history,3,new PlaceOrder(1001,Direction.SELL,n("99"),n("2"))).orderId();
        var restored=new SecuritiesEngine(catalog()); restored.restore(engine.snapshot());
        var event=history.add(2,new PlaceOrder(1001,Direction.BUY,n("105"),n("5")));
        var result=restored.execute(event,SecuritiesEngine.Mode.LIVE).orElseThrow();
        assertEquals(List.of(cheaper,first,second),result.trades().stream().map(ExecutionResult.Trade::makerOrderId).toList());
        engine.execute(event,SecuritiesEngine.Mode.LIVE); assertEquals(engine.snapshot(),restored.snapshot());
    }

    /** 所有可能切点均比较全量与快照增量恢复，随后继续撤单证明恢复订单可操作。 */
    @Test void everySnapshotBoundaryMatchesFullReplay() {
        var history=new History();
        history.add(2,new CreditCash(Currency.USD,n("10000")));
        for(long security=1001;security<=1005;security++) {
            history.add(3,new CreditPosition(security,n("100")));
            history.add(3,new PlaceOrder(security,Direction.SELL,n("20"),n("10")));
            history.add(2,new PlaceOrder(security,Direction.BUY,n("21"),n("4")));
        }
        history.add(2,new PlaceOrder(1001,Direction.BUY,n("10"),n("5")));
        var full=new SecuritiesEngine(catalog()); history.events.forEach(e->full.execute(e,SecuritiesEngine.Mode.REPLAY));
        for(int split=0;split<=history.events.size();split++) {
            var prefix=new SecuritiesEngine(catalog()); history.events.subList(0,split).forEach(e->prefix.execute(e,SecuritiesEngine.Mode.REPLAY));
            var restored=new SecuritiesEngine(catalog()); restored.restore(JsonUtil.readJson(JsonUtil.writeJson(prefix.snapshot()),SecuritiesSnapshot.class));
            for(var event:history.events.subList(split,history.events.size())) assertTrue(restored.execute(event,SecuritiesEngine.Mode.REPLAY).isEmpty());
            assertEquals(full.snapshot(),restored.snapshot(),"切点 "+split);
            var order=restored.snapshot().orders().get(0);
            var cancel=new SequencedEvent(2,1,history.events.size()+1,history.events.size(),"after:restore",order.userId(),9999,new CancelOrder(order.securityId(),order.id()));
            assertTrue(restored.execute(cancel,SecuritiesEngine.Mode.LIVE).orElseThrow().accepted());
        }
    }

    /** 重复事件不再执行；资金/持仓不足和非法数量是业务拒绝，仍推进日志位置。 */
    @Test void duplicatesAndBusinessRejectionsDoNotChangeBalances() {
        var engine=new SecuritiesEngine(catalog()); var history=new History();
        var credit=history.add(2,new CreditCash(Currency.USD,n("100")));
        engine.execute(credit,SecuritiesEngine.Mode.LIVE); var before=engine.snapshot();
        assertTrue(engine.execute(credit,SecuritiesEngine.Mode.LIVE).isEmpty()); assertEquals(before,engine.snapshot());
        assertFalse(apply(engine,history,2,new PlaceOrder(1001,Direction.BUY,n("100"),n("2"))).accepted());
        assertFalse(apply(engine,history,2,new PlaceOrder(1001,Direction.SELL,n("100"),n("1"))).accepted());
        assertFalse(apply(engine,history,2,new PlaceOrder(1001,Direction.BUY,n("1.001"),n("1"))).accepted());
        assertFalse(apply(engine,history,2,new PlaceOrder(1001,Direction.BUY,n("1"),n("0.1"))).accepted());
        amount("100",engine.account(2).cash().available()); assertEquals(5,engine.sequence());
    }

    /** 断链停止服务和快照保存，不能跳过丢失事件继续处理。 */
    @Test void eventGapFailsClosed() {
        var engine=new SecuritiesEngine(catalog());
        assertThrows(IllegalStateException.class,()->engine.execute(new SequencedEvent(2,1,3,2,"gap",2,1,new CreditCash(Currency.USD,n("100"))),SecuritiesEngine.Mode.LIVE));
        assertEquals(0,engine.sequence()); assertThrows(IllegalStateException.class,engine::snapshot);
        assertThrows(IllegalStateException.class,()->engine.account(2));
    }

    /** 冻结不等于活动订单、旧格式和错目录都拒绝，失败恢复不污染目标引擎。 */
    @Test void rejectsCorruptAndIncompatibleSnapshots() {
        var engine=new SecuritiesEngine(catalog()); var history=new History(); seed(engine,history,1001);
        apply(engine,history,2,new PlaceOrder(1001,Direction.BUY,n("10"),n("2")));
        var valid=engine.snapshot(); var cash=new ArrayList<>(valid.cash());
        cash.replaceAll(c->c.userId()==2 ? new SecuritiesSnapshot.Cash(2,c.available(),n("0")) : c);
        var target=new SecuritiesEngine(catalog());
        assertThrows(IllegalStateException.class,()->target.restore(new SecuritiesSnapshot(2,1,valid.catalogHash(),valid.sequenceId(),cash,valid.positions(),valid.orders(),valid.markets())));
        assertEquals(0,target.sequence());
        assertThrows(IllegalStateException.class,()->target.restore(new SecuritiesSnapshot(1,1,valid.catalogHash(),valid.sequenceId(),valid.cash(),valid.positions(),valid.orders(),valid.markets())));
        assertThrows(IllegalStateException.class,()->target.restore(new SecuritiesSnapshot(2,1,"wrong",valid.sequenceId(),valid.cash(),valid.positions(),valid.orders(),valid.markets())));
        target.restore(valid); assertEquals(valid,target.snapshot());
    }

    /** 内部负债账户不能作为普通交易用户，禁止借配给命令操纵记账对手。 */
    @Test void rejectsInternalAccountTrading() {
        var engine=new SecuritiesEngine(catalog()); var history=new History();
        assertFalse(apply(engine,history,1,new CreditCash(Currency.USD,n("1"))).accepted());
        assertTrue(engine.snapshot().cash().isEmpty());
    }
}
