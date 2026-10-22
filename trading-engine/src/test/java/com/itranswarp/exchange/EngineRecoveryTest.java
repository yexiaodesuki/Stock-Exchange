/*
 * 启动恢复回归测试：使用内存事件仓储及消息、Redis 替身，不连接用户的 Docker 服务。
 * 覆盖启动顺序、完整状态、分页、就绪门控、失败关闭和恢复后的实时事件衔接。
 */
package com.itranswarp.exchange;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.clearing.ClearingService;
import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.enums.UserType;
import com.itranswarp.exchange.match.MatchEngine;
import com.itranswarp.exchange.message.AbstractMessage;
import com.itranswarp.exchange.message.event.AbstractEvent;
import com.itranswarp.exchange.message.event.OrderCancelEvent;
import com.itranswarp.exchange.message.event.OrderRequestEvent;
import com.itranswarp.exchange.message.event.TransferEvent;
import com.itranswarp.exchange.messaging.BatchMessageHandler;
import com.itranswarp.exchange.messaging.MessageConsumer;
import com.itranswarp.exchange.messaging.MessageProducer;
import com.itranswarp.exchange.messaging.Messaging;
import com.itranswarp.exchange.messaging.MessagingFactory;
import com.itranswarp.exchange.order.OrderService;
import com.itranswarp.exchange.redis.RedisConfiguration;
import com.itranswarp.exchange.redis.RedisService;
import com.itranswarp.exchange.store.StoreService;
import com.itranswarp.exchange.snapshot.SnapshotService;
import com.itranswarp.exchange.util.JsonUtil;
import com.itranswarp.exchange.web.api.InternalTradingEngineApiController;

class EngineRecoveryTest {
    private static final long TIME = Instant.parse("2026-01-02T03:04:05Z").toEpochMilli();
    private static final long BUYER = 1001;
    private static final long SELLER = 1002;

    /** 新状态只允许一个恢复流程进入，不能直接跳过恢复入口启动实时服务。 */
    @Test
    void recoveryCannotStartTwiceOrBeBypassed() {
        TradingEngineService engine = bareEngine();
        assertThrows(IllegalStateException.class, engine::startAfterRecovery);
        engine.beginRecovery();
        assertThrows(IllegalStateException.class, engine::beginRecovery);
    }

    /** 最终一致性校验发现负余额时禁止就绪，不以历史能够执行完成代替状态正确。 */
    @Test
    void finalValidationFailurePreventsRuntimeStart() throws Exception {
        TransferEvent bad = deposit(1, SELLER, AssetEnum.USD, "1");
        bad.fromUserId = BUYER;
        try (Fixture f = new Fixture(List.of(bad))) {
            assertThrows(IllegalStateException.class, f::recover);
            assertTrue(f.trace.isEmpty());
            assertEquals(TradingEngineService.RecoveryState.FAILED, f.engine.getRecoveryState());
        }
    }

    /** 完整历史恢复的资产、冻结、活动订单及优先级与不停机执行一致，旧输出为零。 */
    @Test
    void restoresAllStateBeforeOpeningConsumer() throws Exception {
        try (Fixture f = new Fixture(history())) {
            TradingEngineService baseline = bareEngine();
            baseline.processMessages(history());
            f.recover();
            assertEquals(state(baseline), state(f.engine));
            assertEquals(TradingEngineService.RecoveryState.READY, f.engine.getRecoveryState());
            assertEquals("earliest", f.messaging.resetPolicy);
            assertEquals(List.of("restore", "producer", "consumer"), f.trace);
            assertEquals(6, f.messaging.positionAtConsumerStart);
            assertEquals(6, Long.parseLong(f.redis.values[0]));
            assertEquals("restore", f.redis.values[2]);
            assertTrue(f.redis.values[1].contains("110.00"));
            assertEquals(0, f.messaging.sent.get());
            assertEquals(0, f.redis.notifications.get());
            assertQueuesEmpty(f.engine);
            f.engine.validate();
        }
    }

    /** 超过页容量时逐页恢复而不是只恢复第一批，也不长期追逐启动后的新增历史。 */
    @Test
    void pagesHistoryBeyondOneThousandEvents() throws Exception {
        List<AbstractEvent> events = new ArrayList<>();
        for (int i = 1; i <= 2005; i++) {
            events.add(deposit(i, BUYER, AssetEnum.USD, "1"));
        }
        try (Fixture f = new Fixture(events)) {
            f.recover();
            assertEquals(3, f.store.pageCalls);
            assertEquals(2005, f.engine.getLastSequenceId());
            assertEquals(0, new BigDecimal("2005").compareTo(
                    f.engine.assetService.getAsset(BUYER, AssetEnum.USD).getAvailable()));
            assertQueuesEmpty(f.engine);
        }
    }

    /** 空库可以就绪并发布零序号空盘口，但不能制造默认充值或虚构用户资产。 */
    @Test
    void emptyHistoryStartsWithoutInventingAssets() throws Exception {
        try (Fixture f = new Fixture(List.of())) {
            f.recover();
            assertEquals(0, f.store.pageCalls);
            assertTrue(f.engine.assetService.getUserAssets().isEmpty());
            assertEquals("0", f.redis.values[0]);
            f.engine.requireReady();
        }
    }

    /** 恢复中所有资产及活动订单查询返回 503，而不是空对象、空订单或不存在订单。 */
    @Test
    void queriesRejectRecoveringAndFailedStates() throws Exception {
        try (Fixture f = new Fixture(history())) {
            MockMvc mvc = mvc(f.engine);
            for (String path : List.of("/internal/status", "/internal/1001/assets",
                    "/internal/1001/orders", "/internal/1001/orders/1")) {
                assertEquals(503, mvc.perform(get(path)).andReturn().getResponse().getStatus());
            }
            f.engine.failRecovery();
            String error = mvc.perform(get("/internal/1001/assets")).andReturn().getResponse().getContentAsString();
            assertTrue(error.contains("ENGINE_UNAVAILABLE"));
            assertTrue(error.contains("FAILED"));
        }
    }

    /** 恢复后查询确实返回已恢复余额、挂单和 READY 状态，不只是探针固定返回成功。 */
    @Test
    void queriesReturnRecoveredBalancesAndOrders() throws Exception {
        try (Fixture f = new Fixture(history())) {
            f.recover();
            MockMvc mvc = mvc(f.engine);
            assertEquals(200, mvc.perform(get("/internal/status")).andReturn().getResponse().getStatus());
            String assets = mvc.perform(get("/internal/1001/assets")).andReturn().getResponse().getContentAsString();
            assertTrue(assets.contains("989.5000"));
            String orders = mvc.perform(get("/internal/1002/orders")).andReturn().getResponse().getContentAsString();
            assertTrue(orders.contains("110.00"));
        }
    }

    /** 固定上界后新增的事件由消费者处理；旧 Kafka 消息跳过且不重复充值或历史输出。 */
    @Test
    void handsOffNewEventsAndSkipsKafkaDuplicates() throws Exception {
        try (Fixture f = new Fixture(history())) {
            AbstractEvent newEvent = deposit(7, BUYER, AssetEnum.USD, "10");
            f.store.afterCapture = () -> f.store.events.add(newEvent);
            f.recover();
            assertEquals(6, f.engine.getLastSequenceId());
            String before = state(f.engine);
            f.messaging.deliver(history());
            assertEquals(before, state(f.engine));
            assertQueuesEmpty(f.engine);
            f.messaging.deliver(List.of(newEvent));
            assertEquals(7, f.engine.getLastSequenceId());
            assertEquals(0, new BigDecimal("999.5").compareTo(
                    f.engine.assetService.getAsset(BUYER, AssetEnum.USD).getAvailable()));
        }
    }

    /** 已恢复的卖单可以继续被新买单成交，恢复没有复制出相互脱离的订单对象。 */
    @Test
    void recoveredOrdersCanContinueTrading() throws Exception {
        try (Fixture f = new Fixture(history())) {
            f.recover();
            long id = 6 * 10000 + 202601;
            assertSame(f.engine.orderService.getOrder(id), f.engine.matchEngine.sellBook.getFirst());
            f.messaging.deliver(List.of(order(7, BUYER, Direction.BUY, "115", "0.20")));
            assertTrue(f.engine.orderService.getActiveOrders().isEmpty());
            assertEquals(7, f.engine.getLastSequenceId());
            assertEquals(0, new BigDecimal("967.5").compareTo(
                    f.engine.assetService.getAsset(BUYER, AssetEnum.USD).getAvailable()));
            f.engine.validate();
        }
    }

    /** 非连续历史必须失败，不能创建消费者、发布盘口或把部分恢复资产开放给查询。 */
    @Test
    void brokenHistoryFailsClosed() throws Exception {
        try (Fixture f = new Fixture(List.of(deposit(1, BUYER, AssetEnum.USD, "100"),
                deposit(3, BUYER, AssetEnum.USD, "10")))) {
            assertThrows(IllegalStateException.class, f::recover);
            assertEquals(TradingEngineService.RecoveryState.FAILED, f.engine.getRecoveryState());
            assertTrue(f.trace.isEmpty());
            assertThrows(ApiException.class, f.engine::requireReady);
        }
    }

    /** 固定上界尚未达到但读出空页时明确失败，不将缺失历史误认为恢复结束。 */
    @Test
    void missingPageFailsClosed() throws Exception {
        try (Fixture f = new Fixture(history())) {
            f.store.emptyPage = true;
            assertThrows(IllegalStateException.class, f::recover);
            assertTrue(f.trace.isEmpty());
            assertEquals(0, f.engine.getLastSequenceId());
        }
    }

    /** 数据库无法读取时不开放业务，也不启动后台线程或执行任何缓存发布。 */
    @Test
    void databaseFailureNeverStartsRuntime() throws Exception {
        try (Fixture f = new Fixture(history())) {
            f.store.failRead = true;
            assertThrows(IllegalStateException.class, f::recover);
            assertTrue(f.trace.isEmpty());
            assertThrows(ApiException.class, f.engine::requireReady);
        }
    }

    /** Redis 比恢复位置更高时拒绝开放，而不是覆盖更新状态或假装盘口发布成功。 */
    @Test
    void newerRedisStatePreventsReadiness() throws Exception {
        try (Fixture f = new Fixture(history())) {
            f.redis.accept = false;
            assertThrows(IllegalStateException.class, f::recover);
            assertEquals(List.of("restore"), f.trace);
            assertThrows(ApiException.class, f.engine::requireReady);
        }
    }

    /** 消费者初始化失败后全部输出线程收到关闭信号，不残留可提供交易的半启动引擎。 */
    @Test
    void consumerStartFailureCleansUpThreads() throws Exception {
        try (Fixture f = new Fixture(history())) {
            f.messaging.failStart = true;
            assertThrows(IllegalStateException.class, f::recover);
            f.joinThreads();
            assertEquals(TradingEngineService.RecoveryState.FAILED, f.engine.getRecoveryState());
        }
    }

    /** 成功停机后状态不可用，并验证全部五个线程及消费者都已关闭。 */
    @Test
    void shutdownClosesBusinessAndEveryOutputThread() throws Exception {
        try (Fixture f = new Fixture(history())) {
            f.recover();
            f.engine.destroy();
            f.joinThreads();
            assertTrue(f.messaging.stopped);
            assertEquals(TradingEngineService.RecoveryState.STOPPED, f.engine.getRecoveryState());
            assertThrows(ApiException.class, f.engine::requireReady);
        }
    }

    /** 恢复结束后的单独撤单应按撤单序号生成盘口快照，不能停留在最后一次下单序号。 */
    @Test
    void cancellationAdvancesPublishedBookVersion() {
        TradingEngineService engine = bareEngine();
        engine.replayEvents(history());
        engine.processMessages(List.of(cancel(7, SELLER, 6 * 10000 + 202601)));
        Object book = ReflectionTestUtils.getField(engine, "latestOrderBook");
        assertEquals(7L, ReflectionTestUtils.getField(book, "sequenceId"));
        assertTrue(engine.matchEngine.sellBook.book.isEmpty());
        engine.validate();
    }

    /** 构造冻结、部分成交、撤单和最终剩余挂单，确保恢复覆盖多种资产状态。 */
    private List<AbstractEvent> history() {
        return List.of(deposit(1, BUYER, AssetEnum.USD, "1000"), deposit(2, SELLER, AssetEnum.BTC, "3"),
                order(3, SELLER, Direction.SELL, "105.00", "0.50"),
                order(4, BUYER, Direction.BUY, "110.00", "0.10"),
                cancel(5, SELLER, 3 * 10000 + 202601),
                order(6, SELLER, Direction.SELL, "110.00", "0.20"));
    }

    /** 创建纯内存引擎，不触发真实 Spring 初始化及数据库或消息客户端。 */
    private TradingEngineService bareEngine() {
        TradingEngineService engine = new TradingEngineService();
        engine.zoneId = ZoneId.of("UTC");
        engine.assetService = new AssetService();
        engine.orderService = new OrderService(engine.assetService);
        engine.matchEngine = new MatchEngine();
        engine.clearingService = new ClearingService(engine.assetService, engine.orderService);
        return engine;
    }

    /** 用真实控制器和异常处理器测试 HTTP 状态，不启动监听端口。 */
    private MockMvc mvc(TradingEngineService engine) {
        InternalTradingEngineApiController controller = new InternalTradingEngineApiController();
        ReflectionTestUtils.setField(controller, "engine", engine);
        ReflectionTestUtils.setField(controller, "assetService", engine.assetService);
        ReflectionTestUtils.setField(controller, "orderService", engine.orderService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 设置同一事件链及固定时间，使两次执行得到相同订单标识。 */
    private <T extends AbstractEvent> T identify(T event, long seq) {
        event.sequenceId = seq;
        event.previousId = seq - 1;
        event.createdAt = TIME + seq;
        return event;
    }

    /** 构造内部负债账户充值事件，不依赖 UI 的注册充值路径。 */
    private TransferEvent deposit(long seq, long user, AssetEnum asset, String amount) {
        TransferEvent e = identify(new TransferEvent(), seq);
        e.fromUserId = UserType.DEBT.getInternalUserId();
        e.toUserId = user;
        e.asset = asset;
        e.amount = new BigDecimal(amount);
        return e;
    }

    /** 构造限价订单，实际冻结和清算交由引擎执行。 */
    private OrderRequestEvent order(long seq, long user, Direction direction, String price, String quantity) {
        OrderRequestEvent e = identify(new OrderRequestEvent(), seq);
        e.userId = user;
        e.direction = direction;
        e.price = new BigDecimal(price);
        e.quantity = new BigDecimal(quantity);
        return e;
    }

    /** 构造指定活动订单的撤单事件。 */
    private OrderCancelEvent cancel(long seq, long user, long orderId) {
        OrderCancelEvent e = identify(new OrderCancelEvent(), seq);
        e.userId = user;
        e.refOrderId = orderId;
        return e;
    }

    /** 比较全量权威内存状态和已应用位置，不包含实时输出任务。 */
    private String state(TradingEngineService engine) {
        return JsonUtil.writeJson(List.of(new TreeMap<>(engine.assetService.getUserAssets()),
                new TreeMap<>(engine.orderService.getActiveOrders()),
                new ArrayList<>(engine.matchEngine.buyBook.book.values()),
                new ArrayList<>(engine.matchEngine.sellBook.book.values()),
                engine.matchEngine.marketPrice, engine.getLastSequenceId()));
    }

    /** 检查恢复没有安排历史数据库写入、HTTP 结果、Tick 或通知。 */
    private void assertQueuesEmpty(TradingEngineService engine) {
        for (String name : List.of("orderQueue", "matchQueue", "tickQueue", "notificationQueue", "apiResultQueue")) {
            assertTrue(((Queue<?>) ReflectionTestUtils.getField(engine, name)).isEmpty(), name);
        }
    }

    private class Fixture implements AutoCloseable {
        final List<String> trace = new ArrayList<>();
        final TradingEngineService engine = bareEngine();
        final MemoryStore store;
        final FakeRedis redis = new FakeRedis(trace);
        final FakeMessaging messaging = new FakeMessaging(engine, trace);

        /** 装配真实引擎和隔离替身，记录对外初始化顺序。 */
        Fixture(List<AbstractEvent> events) {
            store = new MemoryStore(events);
            engine.storeService = store;
            engine.redisService = redis;
            engine.messagingFactory = messaging;
        }

        /** 调用与生产 ApplicationRunner 完全相同的恢复入口。 */
        void recover() {
            // 第二阶段用例保持无快照前提；真实快照路径由第三阶段专用用例验证。
            SnapshotService snapshots = new SnapshotService(engine, null) {
                /** 本组测试不提供快照，验证原全量事件恢复行为。 */
                @Override
                public void restoreLatest(long target) { }
                /** 本组测试不写数据库，快照持久化由专用测试验证。 */
                @Override
                public void saveRecoveredState() { }
                /** 本组测试不创建周期任务，专注消费者及输出线程生命周期。 */
                @Override
                public void startPeriodic() { }
            };
            new EngineRecoveryService(engine, store, snapshots).run(null);
        }

        /** 等待所有已创建线程退出，显式发现启动失败或停机产生的线程泄漏。 */
        void joinThreads() throws InterruptedException {
            for (String name : List.of("tickThread", "notifyThread", "apiResultThread", "orderBookThread", "dbThread")) {
                Thread thread = (Thread) ReflectionTestUtils.getField(engine, name);
                if (thread != null) {
                    thread.join(2000);
                    assertFalse(thread.isAlive(), name);
                }
            }
        }

        /** 测试结束释放线程和未连接的 Redis 客户端对象，不清理任何外部服务数据。 */
        @Override
        public void close() throws Exception {
            engine.destroy();
            joinThreads();
            redis.shutdown();
        }
    }

    private static class MemoryStore extends StoreService {
        final List<AbstractEvent> events;
        int pageCalls;
        boolean failRead;
        boolean emptyPage;
        Runnable afterCapture;

        /** 保存独立历史列表，允许模拟恢复期间追加事件。 */
        MemoryStore(List<AbstractEvent> events) {
            this.events = new ArrayList<>(events);
        }

        /** 返回捕获时的上界，随后模拟数据库继续追加新事件。 */
        @Override
        public long getLatestEventSequenceId() {
            if (failRead) throw new IllegalStateException("模拟数据库不可用");
            long target = events.isEmpty() ? 0 : events.get(events.size() - 1).sequenceId;
            if (afterCapture != null) afterCapture.run();
            return target;
        }

        /** 与生产分页条件一致，记录调用次数并模拟缺失页。 */
        @Override
        public List<AbstractEvent> loadEventsFromDb(long last, long upper, int size) {
            pageCalls++;
            if (emptyPage) return List.of();
            return events.stream().filter(e -> e.sequenceId > last && e.sequenceId <= upper).limit(size).toList();
        }

        /** 实时历史写入在测试中不访问数据库，恢复阶段通过空队列另行断言。 */
        @Override
        public void insertIgnore(List<? extends com.itranswarp.exchange.model.support.EntitySupport> rows) {
        }
    }

    private static class FakeRedis extends RedisService {
        final List<String> trace;
        final AtomicInteger notifications = new AtomicInteger();
        String[] values;
        boolean accept = true;

        /** 创建不借出连接的客户端，用覆盖方法替代所有 Redis 操作。 */
        FakeRedis(List<String> trace) {
            super(config());
            this.trace = trace;
        }

        /** 提供合法但不连接的本机配置，实际数据路径均由替身覆盖。 */
        private static RedisConfiguration config() {
            RedisConfiguration c = new RedisConfiguration();
            c.setHost("localhost");
            c.setPort(6379);
            c.setPassword("");
            return c;
        }

        /** 模拟脚本加载，不调用 Redis。 */
        @Override
        public String loadScriptFromClassPath(String path) { return "fake-sha"; }

        /** 记录恢复盘口参数及初始化顺序，可模拟 Redis 拒绝旧版本。 */
        @Override
        public Boolean executeScriptReturnBoolean(String sha, String[] keys, String[] values) {
            this.values = values;
            if (values.length == 3) trace.add("restore");
            return accept;
        }

        /** 仅计数实时通知，恢复历史不应触发该通道。 */
        @Override
        public void publish(String topic, String json) { notifications.incrementAndGet(); }
    }

    private static class FakeMessaging extends MessagingFactory {
        final TradingEngineService engine;
        final List<String> trace;
        final AtomicInteger sent = new AtomicInteger();
        BatchMessageHandler<AbstractEvent> handler;
        String resetPolicy;
        long positionAtConsumerStart;
        boolean failStart;
        boolean stopped;

        /** 持有引擎用于断言消费者创建发生在恢复之后。 */
        FakeMessaging(TradingEngineService engine, List<String> trace) {
            this.engine = engine;
            this.trace = trace;
        }

        /** 模拟生产者并计数新输出，避免 Kafka 网络操作。 */
        @Override
        public <T extends AbstractMessage> MessageProducer<T> createMessageProducer(Messaging.Topic topic, Class<T> type) {
            trace.add("producer");
            // 实时发送仅计数，不投递到外部 Topic。
            return message -> sent.incrementAndGet();
        }

        /** 捕获真实回调和重置策略，模拟创建失败或正常停止。 */
        @Override
        @SuppressWarnings("unchecked")
        public <T extends AbstractMessage> MessageConsumer createBatchMessageListener(Messaging.Topic topic,
                String group, BatchMessageHandler<T> handler, CommonErrorHandler errors, String policy) {
            trace.add("consumer");
            positionAtConsumerStart = engine.getLastSequenceId();
            resetPolicy = policy;
            if (failStart) throw new IllegalStateException("模拟消费者创建失败");
            this.handler = (BatchMessageHandler<AbstractEvent>) handler;
            // 关闭回调仅记录资源释放，不访问 Kafka。
            return () -> stopped = true;
        }

        /** 通过真实 Kafka 回调路径投递事件，测试恢复与实时消费的衔接。 */
        void deliver(List<AbstractEvent> events) { handler.processMessages(events); }
    }
}
