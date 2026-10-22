/*
 * 第一阶段回归测试：直接构造内存引擎，验证重放状态与副作用隔离，不启动 Spring 或外部服务。
 */
package com.itranswarp.exchange;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.assets.Transfer;
import com.itranswarp.exchange.clearing.ClearingService;
import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.enums.OrderStatus;
import com.itranswarp.exchange.enums.UserType;
import com.itranswarp.exchange.match.MatchEngine;
import com.itranswarp.exchange.message.ApiResultMessage;
import com.itranswarp.exchange.message.event.AbstractEvent;
import com.itranswarp.exchange.message.event.OrderCancelEvent;
import com.itranswarp.exchange.message.event.OrderRequestEvent;
import com.itranswarp.exchange.message.event.TransferEvent;
import com.itranswarp.exchange.model.trade.OrderEntity;
import com.itranswarp.exchange.order.OrderService;
import com.itranswarp.exchange.util.JsonUtil;

class TradingEngineReplayTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 1002L;
    private static final long TIMESTAMP = Instant.parse("2026-01-02T03:04:05Z").toEpochMilli();
    private static final List<String> OUTPUT_QUEUES = List.of(
            "apiResultQueue", "notificationQueue", "tickQueue", "orderQueue", "matchQueue");

    /** 逐个事件比较实时与重放状态，覆盖充值、挂单、部分成交、撤单、完全成交及剩余买单。 */
    @Test
    void replayMatchesLiveStateAtEveryEventWithoutOutputs() {
        TradingEngineService live = engine();
        TradingEngineService replay = engine();
        for (AbstractEvent event : scenario()) {
            live.processMessages(List.of(event));
            replay.replayEvents(List.of(event));
            assertStateEquals(live, replay);
            assertNoOutputs(replay);
            live.validate();
            replay.validate();
        }
        assertMoney("959.00", replay.assetService.getAsset(BUYER, AssetEnum.USD).getAvailable());
        assertMoney("20.00", replay.assetService.getAsset(BUYER, AssetEnum.USD).getFrozen());
        assertMoney("0.20", replay.assetService.getAsset(BUYER, AssetEnum.BTC).getAvailable());
        assertMoney("2.80", replay.assetService.getAsset(SELLER, AssetEnum.BTC).getAvailable());
        assertMoney("21.00", replay.assetService.getAsset(SELLER, AssetEnum.USD).getAvailable());
        assertEquals(OrderStatus.PENDING, replay.orderService.getOrder(orderId(8)).status);
        assertEquals(8, replay.getLastSequenceId());
    }

    /** 实时成交仍生成响应、双方通知、两条成交明细、一笔 Tick 和完成订单任务。 */
    @Test
    void liveModePreservesSuccessfulTradeOutputs() {
        TradingEngineService engine = engine();
        engine.processMessages(scenario().subList(0, 4));
        assertEquals(2, queue(engine, "apiResultQueue").size());
        assertEquals(2, queue(engine, "notificationQueue").size());
        assertEquals(1, queue(engine, "tickQueue").size());
        assertEquals(1, ((List<?>) queue(engine, "orderQueue").peek()).size());
        assertEquals(2, ((List<?>) queue(engine, "matchQueue").peek()).size());
        assertNotNull(ReflectionTestUtils.getField(engine, "latestOrderBook"));
    }

    /** 成功撤单在重放中释放冻结资产，但实时路径继续产生响应与私人通知。 */
    @Test
    void cancelReleasesAssetsAndOnlyLiveModeEmitsOutputs() {
        TradingEngineService live = engine();
        TradingEngineService replay = engine();
        live.processMessages(scenario().subList(0, 5));
        replay.replayEvents(scenario().subList(0, 5));
        assertStateEquals(live, replay);
        assertNull(replay.orderService.getOrder(orderId(3)));
        assertMoney("0.00", replay.assetService.getAsset(SELLER, AssetEnum.BTC).getFrozen());
        assertEquals(3, queue(live, "apiResultQueue").size());
        assertEquals(3, queue(live, "notificationQueue").size());
        assertNoOutputs(replay);
    }

    /** 余额不足和错误归属撤单仍推进事件位置，重放不回复旧失败请求。 */
    @Test
    void businessRejectionsHaveIdenticalStateWithoutReplayResponses() {
        TradingEngineService live = engine();
        TradingEngineService replay = engine();
        List<AbstractEvent> events = List.of(
                deposit(1, BUYER, AssetEnum.USD, "1000.00"),
                deposit(2, SELLER, AssetEnum.BTC, "3.00"),
                order(3, BUYER, Direction.BUY, "100.00", "20.00"),
                order(4, SELLER, Direction.SELL, "100.00", "0.50"),
                cancel(5, BUYER, orderId(4)),
                cancel(6, BUYER, 999L));
        live.processMessages(events);
        replay.replayEvents(events);
        assertStateEquals(live, replay);
        assertEquals(6, replay.getLastSequenceId());
        assertEquals(4, queue(live, "apiResultQueue").size());
        assertNotNull(((ApiResultMessage) queue(live, "apiResultQueue").peek()).error);
        assertEquals(OrderStatus.PENDING, replay.orderService.getOrder(orderId(4)).status);
        assertNoOutputs(replay);
        replay.validate();
    }

    /** 已应用事件再次重放或经实时入口到达时，不重复扣款或生成输出。 */
    @Test
    void duplicatesDoNotChangeRecoveredStateOrOutputs() {
        TradingEngineService engine = engine();
        List<AbstractEvent> events = scenario();
        engine.replayEvents(events);
        String state = state(engine);
        engine.replayEvents(events);
        engine.processMessages(events);
        assertEquals(state, state(engine));
        assertNoOutputs(engine);
    }

    /** 重放只接受已有连续历史，不调用数据库补洞；失败后禁止继续重放。 */
    @Test
    void brokenChainFailsBeforeApplyingEventAndPoisonsReplay() {
        TradingEngineService engine = engine();
        engine.replayEvents(List.of(deposit(1, BUYER, AssetEnum.USD, "1000.00")));
        String state = state(engine);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.replayEvents(List.of(deposit(3, BUYER, AssetEnum.USD, "10.00"))));
        assertTrue(error.getMessage().contains("断链"));
        assertEquals(state, state(engine));
        assertTrue(engine.fatalError);
        assertThrows(IllegalStateException.class, () -> engine.replayEvents(List.of(
                deposit(2, BUYER, AssetEnum.USD, "10.00"))));
        assertNoOutputs(engine);
    }

    /** 非法转账执行异常不推进进度、不生成副作用，也不以 System.exit 终止测试进程。 */
    @Test
    void replayExecutionFailureDoesNotAdvanceProgress() {
        TradingEngineService engine = engine();
        TransferEvent event = deposit(1, BUYER, AssetEnum.USD, "-1.00");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.replayEvents(List.of(event)));
        assertTrue(error.getMessage().contains("sequenceId=1"));
        assertEquals(0, engine.getLastSequenceId());
        assertTrue(engine.fatalError);
        assertNoOutputs(engine);
    }

    /** 调试校验发现损坏状态时，以可捕获的重放异常失败，不推进位置或退出整个进程。 */
    @Test
    void replayValidationFailureDoesNotAdvanceProgress() {
        TradingEngineService engine = engine();
        engine.assetService.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, BUYER, SELLER,
                AssetEnum.USD, BigDecimal.ONE, false);
        engine.debugMode = true;
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.replayEvents(List.of(deposit(1, BUYER, AssetEnum.BTC, "1.00"))));
        assertTrue(error.getCause().getMessage().contains("校验失败"));
        assertEquals(0, engine.getLastSequenceId());
        assertTrue(engine.fatalError);
        assertNoOutputs(engine);
    }

    /** 未支持的历史事件必须失败，而不是悄悄跳过并报告恢复成功。 */
    @Test
    void unsupportedReplayEventFailsExplicitly() {
        TradingEngineService engine = engine();
        AbstractEvent event = identify(new AbstractEvent(), 1);
        assertThrows(IllegalStateException.class, () -> engine.replayEvents(List.of(event)));
        assertEquals(0, engine.getLastSequenceId());
        assertNoOutputs(engine);
    }

    /** 重放后的真实活动订单可以被后续实时事件成交，并正常生成新输出。 */
    @Test
    void liveTradingContinuesAfterReplayWithoutHistoricalOutputs() {
        TradingEngineService engine = engine();
        engine.replayEvents(scenario().subList(0, 3));
        assertNoOutputs(engine);
        OrderEntity maker = engine.orderService.getOrder(orderId(3));
        assertSame(maker, engine.matchEngine.sellBook.getFirst());
        assertSame(maker, engine.orderService.getUserOrders(SELLER).get(maker.id));
        engine.processMessages(List.of(order(4, BUYER, Direction.BUY, "105.00", "0.50")));
        assertEquals(4, engine.getLastSequenceId());
        assertTrue(engine.orderService.getActiveOrders().isEmpty());
        assertEquals(1, queue(engine, "apiResultQueue").size());
        assertEquals(2, queue(engine, "notificationQueue").size());
        assertEquals(1, queue(engine, "tickQueue").size());
        assertEquals(2, ((List<?>) queue(engine, "orderQueue").peek()).size());
        assertMoney("950.00", engine.assetService.getAsset(BUYER, AssetEnum.USD).getAvailable());
        engine.validate();
    }

    /** 创建固定时区的纯内存引擎；不执行初始化、不启动输出线程，也不提供外部客户端。 */
    private TradingEngineService engine() {
        TradingEngineService engine = new TradingEngineService();
        engine.zoneId = ZoneId.of("UTC");
        engine.assetService = new AssetService();
        engine.orderService = new OrderService(engine.assetService);
        engine.matchEngine = new MatchEngine();
        engine.clearingService = new ClearingService(engine.assetService, engine.orderService);
        return engine;
    }

    /** 构造同时覆盖部分成交、撤单、完全成交以及未成交冻结额的连续历史。 */
    private List<AbstractEvent> scenario() {
        return List.of(
                deposit(1, BUYER, AssetEnum.USD, "1000.00"),
                deposit(2, SELLER, AssetEnum.BTC, "3.00"),
                order(3, SELLER, Direction.SELL, "100.00", "0.50"),
                order(4, BUYER, Direction.BUY, "105.00", "0.10"),
                cancel(5, SELLER, orderId(3)),
                order(6, SELLER, Direction.SELL, "110.00", "0.10"),
                order(7, BUYER, Direction.BUY, "115.00", "0.10"),
                order(8, BUYER, Direction.BUY, "50.00", "0.40"));
    }

    /** 设置固定事件标识和时间，保证实时及重放生成完全相同的订单 ID。 */
    private <T extends AbstractEvent> T identify(T event, long sequence) {
        event.sequenceId = sequence;
        event.previousId = sequence - 1;
        event.createdAt = TIMESTAMP + sequence;
        event.refId = "request-" + sequence;
        return event;
    }

    /** 构造从内部负债账户向测试用户转入资产的事件。 */
    private TransferEvent deposit(long sequence, long user, AssetEnum asset, String amount) {
        TransferEvent event = identify(new TransferEvent(), sequence);
        event.fromUserId = UserType.DEBT.getInternalUserId();
        event.toUserId = user;
        event.asset = asset;
        event.amount = new BigDecimal(amount);
        event.sufficient = false;
        return event;
    }

    /** 构造指定方向的限价订单事件，不直接操作资产或订单索引。 */
    private OrderRequestEvent order(long sequence, long user, Direction direction, String price, String quantity) {
        OrderRequestEvent event = identify(new OrderRequestEvent(), sequence);
        event.userId = user;
        event.direction = direction;
        event.price = new BigDecimal(price);
        event.quantity = new BigDecimal(quantity);
        return event;
    }

    /** 构造撤单事件，允许测试不存在订单和错误用户归属。 */
    private OrderCancelEvent cancel(long sequence, long user, long orderId) {
        OrderCancelEvent event = identify(new OrderCancelEvent(), sequence);
        event.userId = user;
        event.refOrderId = orderId;
        return event;
    }

    /** 按当前引擎的固定测试年月计算订单 ID，避免使用原测试中无效的常量 ID。 */
    private long orderId(long sequence) {
        return sequence * 10000 + 202601;
    }

    /** 生成稳定排序的状态表示，包含所有账户、活动订单、买卖优先级和处理进度。 */
    private String state(TradingEngineService engine) {
        List<Object> state = new ArrayList<>();
        state.add(new TreeMap<>(engine.assetService.getUserAssets()));
        state.add(new TreeMap<>(engine.orderService.getActiveOrders()));
        state.add(new ArrayList<>(engine.matchEngine.buyBook.book.values()));
        state.add(new ArrayList<>(engine.matchEngine.sellBook.book.values()));
        state.add(engine.matchEngine.marketPrice);
        state.add(engine.getLastSequenceId());
        return JsonUtil.writeJson(state);
    }

    /** 比较业务状态，不把输出队列作为恢复状态的一部分。 */
    private void assertStateEquals(TradingEngineService live, TradingEngineService replay) {
        assertEquals(state(live), state(replay));
    }

    /** 以数值比较金额，避免 BigDecimal 的不同 scale 造成无意义失败。 */
    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    /** 通过测试反射读取私有输出队列，不为测试向生产代码公开队列修改接口。 */
    private Queue<?> queue(TradingEngineService engine, String name) {
        return (Queue<?>) ReflectionTestUtils.getField(engine, name);
    }

    /** 验证全部外部输出通道未被重放触发，包括 Redis 盘口输出标志。 */
    private void assertNoOutputs(TradingEngineService engine) {
        for (String name : OUTPUT_QUEUES) {
            assertTrue(queue(engine, name).isEmpty(), name);
        }
        assertNull(ReflectionTestUtils.getField(engine, "latestOrderBook"));
        assertEquals(Boolean.FALSE, ReflectionTestUtils.getField(engine, "orderBookChanged"));
    }
}
