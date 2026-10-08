/* 一致性快照回归：验证完整状态与增量重放等价，隔离数据库、Redis、Kafka 和周期线程。 */
package com.itranswarp.exchange;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.clearing.ClearingService;
import com.itranswarp.exchange.db.DbTemplate;
import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.message.event.AbstractEvent;
import com.itranswarp.exchange.message.event.OrderRequestEvent;
import com.itranswarp.exchange.message.event.OrderCancelEvent;
import com.itranswarp.exchange.message.event.TransferEvent;
import com.itranswarp.exchange.match.MatchEngine;
import com.itranswarp.exchange.order.OrderService;
import com.itranswarp.exchange.snapshot.EngineSnapshot;
import com.itranswarp.exchange.snapshot.SnapshotCodec;
import com.itranswarp.exchange.snapshot.SnapshotRecord;
import com.itranswarp.exchange.snapshot.SnapshotStore;
import com.itranswarp.exchange.snapshot.SnapshotService;
import com.itranswarp.exchange.store.StoreService;

class SnapshotRecoveryTest {
    private static final long TIME = Instant.parse("2026-01-02T03:04:05Z").toEpochMilli();
    private static final long BUYER = 1001;
    private static final long SELLER = 1002;

    /** 全新空库首次启动先保存零序号快照再开放业务；下次启动能读取该快照且不虚构资金。 */
    @Test
    void freshDatabaseSavesInitialSnapshotBeforeReadyAndCanRestart() {
        TestEngine first = engine();
        MemorySnapshots rows = new MemorySnapshots() {
            /** 初始快照必须在开放业务前写入，防止首次启动绕过持久化保障。 */
            @Override
            public void save(SnapshotRecord record) {
                assertFalse(first.readyStarted);
                assertEquals(TradingEngineService.RecoveryState.RECOVERING, first.getRecoveryState());
                super.save(record);
            }
        };
        EngineSnapshot initial;
        try (Fixture f = new Fixture(first, new MemoryEvents(List.of()), rows)) {
            f.recover();
            assertEquals(1, rows.writes);
            assertEquals(0, rows.lastSaved.sequenceId());
            initial = SnapshotCodec.decode(rows.lastSaved);
            assertTrue(initial.assets().isEmpty());
            assertTrue(initial.orders().isEmpty());
            assertEquals(TradingEngineService.RecoveryState.READY, first.getRecoveryState());
            assertTrue(f.events.cursors.isEmpty());
            assertNoOutputs(first);
            f.snapshots.savePeriodically();
            assertEquals(1, rows.writes);
        }
        MemorySnapshots nextRows = new MemorySnapshots();
        nextRows.rows.add(rows.lastSaved);
        try (Fixture f = new Fixture(engine(), new MemoryEvents(List.of()), nextRows)) {
            f.recover();
            assertEquals(initial, f.engine.captureSnapshot());
            assertTrue(f.engine.readyStarted);
            assertTrue(f.events.cursors.isEmpty());
            assertNoOutputs(f.engine);
        }
    }

    /** 模拟数据库写入阻塞，证明保存快照期间仍能处理新事件，已捕获快照不混入新状态。 */
    @Test
    void databaseWriteDoesNotHoldEngineEventLock() throws Exception {
        TestEngine engine = engine();
        engine.replayEvents(history());
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemorySnapshots rows = new MemorySnapshots() {
            /** 在数据库替身中阻塞，保留完整行写入的原计数与记录行为。 */
            @Override
            public void save(SnapshotRecord record) {
                writing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试写入等待超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试写入被中断", e);
                }
                super.save(record);
            }
        };
        SnapshotService snapshots = new SnapshotService(engine, rows);
        var threads = Executors.newFixedThreadPool(2);
        try {
            // 快照任务先捕获第八个事件边界，再在数据库层等待。
            var saving = threads.submit(snapshots::saveRecoveredState);
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            // 新事件必须在解除数据库阻塞之前完成，证明引擎锁已释放。
            var trading = threads.submit(() -> engine.processMessages(List.of(deposit(9, BUYER, AssetEnum.USD, "10"))));
            trading.get(5, TimeUnit.SECONDS);
            assertEquals(9, engine.getLastSequenceId());
            release.countDown();
            saving.get(5, TimeUnit.SECONDS);
            assertEquals(8, rows.lastSaved.sequenceId());
            assertEquals(8, SnapshotCodec.decode(rows.lastSaved).sequenceId());
        } finally {
            release.countDown();
            threads.shutdownNow();
            snapshots.stop();
        }
    }

    /** 在每个事件边界生成快照，启动后只重放余下事件，结果必须与全量执行逐字段一致。 */
    @Test
    void everySnapshotBoundaryMatchesFullReplayWithoutOutputs() {
        List<AbstractEvent> history = history();
        TestEngine full = engine();
        full.replayEvents(history);
        for (int boundary = 0; boundary <= history.size(); boundary++) {
            TestEngine source = engine();
            source.replayEvents(history.subList(0, boundary));
            MemorySnapshots rows = new MemorySnapshots();
            rows.rows.add(SnapshotCodec.encode(source.captureSnapshot()));
            TestEngine restored = engine();
            MemoryEvents events = new MemoryEvents(history);
            try (Fixture f = new Fixture(restored, events, rows)) {
                f.recover();
                assertEquals(full.captureSnapshot(), restored.captureSnapshot());
                assertTrue(restored.readyStarted);
                if (boundary < history.size()) assertEquals(boundary, events.cursors.get(0));
                else assertTrue(events.cursors.isEmpty());
                assertNoOutputs(restored);
            }
        }
    }

    /** 恢复直接安装活动订单，冻结额不变，各索引引用同一对象，随后可以继续成交或撤单。 */
    @Test
    void restoredOrdersDoNotFreezeAgainAndCanTradeOrCancel() {
        TestEngine source = engine();
        source.replayEvents(history().subList(0, 4));
        EngineSnapshot snapshot = SnapshotCodec.decode(SnapshotCodec.encode(source.captureSnapshot()));
        for (boolean cancel : new boolean[] { false, true }) {
            TestEngine target = engine();
            AssetService originalAssets = target.assetService;
            OrderService originalOrders = target.orderService;
            target.beginRecovery();
            target.restoreSnapshot(snapshot);
            assertSame(originalAssets, target.assetService);
            assertSame(originalOrders, target.orderService);
            assertEquals(snapshot, target.captureSnapshot());
            var maker = target.orderService.getOrder(id(3));
            assertSame(maker, target.matchEngine.sellBook.getFirst());
            assertSame(maker, target.orderService.getUserOrders(SELLER).get(maker.id));
            target.processMessages(List.of(cancel ? cancel(5, SELLER, id(3))
                    : order(5, BUYER, Direction.BUY, "105", "0.4")));
            assertTrue(target.orderService.getActiveOrders().isEmpty());
            target.validate();
        }
    }

    /** 同价订单以创建事件序号重建时间优先级，恢复时不能按用户或集合遍历顺序撮合。 */
    @Test
    void samePricePrioritySurvivesSnapshot() {
        TestEngine source = engine();
        source.replayEvents(List.of(deposit(1, BUYER, AssetEnum.USD, "1000"),
                deposit(2, SELLER, AssetEnum.BTC, "1"),
                order(3, SELLER, Direction.SELL, "100", "0.2"),
                order(4, SELLER, Direction.SELL, "100", "0.2")));
        TestEngine target = engine();
        target.beginRecovery();
        target.restoreSnapshot(SnapshotCodec.decode(SnapshotCodec.encode(source.captureSnapshot())));
        target.processMessages(List.of(order(5, BUYER, Direction.BUY, "100", "0.2")));
        assertNull(target.orderService.getOrder(id(3)));
        assertEquals(id(4), target.matchEngine.sellBook.getFirst().id.longValue());
        target.validate();
    }

    /** 状态取出后继续交易，原快照及其校验值不随可变订单和余额变化。 */
    @Test
    void capturedStateIsDetachedAndImmutable() {
        TestEngine engine = engine();
        engine.replayEvents(history().subList(0, 4));
        EngineSnapshot snapshot = engine.captureSnapshot();
        SnapshotRecord before = SnapshotCodec.encode(snapshot);
        engine.processMessages(List.of(cancel(5, SELLER, id(3))));
        SnapshotRecord after = SnapshotCodec.encode(snapshot);
        assertEquals(before.snapshotData(), after.snapshotData());
        assertEquals(before.checksum(), after.checksum());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.orders().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.assets().clear());
    }

    /** 较新快照损坏时从旧有效快照接续，不从损坏记录污染的资产状态继续。 */
    @Test
    void corruptLatestFallsBackToOlderSnapshot() {
        TestEngine source = engine();
        source.replayEvents(history().subList(0, 3));
        MemorySnapshots rows = new MemorySnapshots();
        rows.rows.add(SnapshotCodec.encode(source.captureSnapshot()));
        source.replayEvents(history().subList(3, 6));
        SnapshotRecord valid = SnapshotCodec.encode(source.captureSnapshot());
        rows.rows.add(new SnapshotRecord(valid.sequenceId(), valid.formatVersion(), valid.snapshotData(), "bad", 0));
        source.replayEvents(history().subList(6, 8));
        MemoryEvents events = new MemoryEvents(history());
        try (Fixture f = new Fixture(engine(), events, rows)) {
            f.recover();
            assertEquals(3L, events.cursors.get(0));
            assertEquals(source.captureSnapshot(), f.engine.captureSnapshot());
        }
    }

    /** 无快照、仅不兼容快照或领先历史的快照都可从零恢复，首份完整快照由程序生成。 */
    @Test
    void unusableSnapshotsFallBackToHistoryAndCreateFirstSnapshot() {
        for (int mode = 0; mode < 3; mode++) {
            MemorySnapshots rows = new MemorySnapshots();
            if (mode == 1) rows.rows.add(new SnapshotRecord(6, 99, "{}", "bad", 0));
            if (mode == 2) rows.rows.add(SnapshotCodec.encode(new EngineSnapshot(1, 99, "UTC",
                    BigDecimal.ZERO, List.of(), List.of())));
            MemoryEvents events = new MemoryEvents(history());
            try (Fixture f = new Fixture(engine(), events, rows)) {
                f.recover();
                assertEquals(0L, events.cursors.get(0));
                assertEquals(8, rows.lastSaved.sequenceId());
                assertEquals(f.engine.captureSnapshot(), SnapshotCodec.decode(rows.lastSaved));
            }
        }
    }

    /** 金额不会经 double 往返，高精度数值及 scale 均保留。 */
    @Test
    void decimalPrecisionIsPreservedExactly() {
        TestEngine engine = engine();
        engine.replayEvents(List.of(deposit(1, BUYER, AssetEnum.USD, "123456789012345678.123456789012345678")));
        EngineSnapshot snapshot = engine.captureSnapshot();
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)));
    }

    /** 冻结不一致的快照安装失败后真实状态仍为空，随后仍可安装正确候选。 */
    @Test
    void invalidStateNeverPartiallyInstalls() {
        TestEngine source = engine();
        source.replayEvents(history().subList(0, 3));
        EngineSnapshot good = source.captureSnapshot();
        List<EngineSnapshot.AssetState> changed = good.assets().stream().map(a ->
                a.userId() == SELLER && a.asset() == AssetEnum.BTC
                        ? new EngineSnapshot.AssetState(a.userId(), a.asset(), a.available().add(a.frozen()), BigDecimal.ZERO)
                        : a).toList();
        EngineSnapshot bad = new EngineSnapshot(1, 3, "UTC", good.marketPrice(), changed, good.orders());
        TestEngine target = engine();
        target.beginRecovery();
        assertThrows(IllegalArgumentException.class, () -> target.restoreSnapshot(bad));
        assertTrue(target.assetService.getUserAssets().isEmpty());
        assertEquals(0, target.getLastSequenceId());
        target.restoreSnapshot(good);
        assertEquals(good, target.captureSnapshot());
    }

    /** 快照时区不兼容时停止启动，不能通过全量重放悄悄生成不同的订单 ID。 */
    @Test
    void zoneChangeFailsStartup() {
        TestEngine source = engine();
        source.replayEvents(history());
        EngineSnapshot s = source.captureSnapshot();
        MemorySnapshots rows = new MemorySnapshots();
        rows.rows.add(SnapshotCodec.encode(new EngineSnapshot(1, s.sequenceId(), "Asia/Tokyo", s.marketPrice(), s.assets(), s.orders())));
        try (Fixture f = new Fixture(engine(), new MemoryEvents(history()), rows)) {
            assertThrows(IllegalStateException.class, f::recover);
            assertFalse(f.engine.readyStarted);
            assertTrue(f.engine.assetService.getUserAssets().isEmpty());
        }
    }

    /** 缺表或读取失败不能假装没有快照；初次持久化失败也不能进入 READY。 */
    @Test
    void snapshotStorageFailuresPreventStartup() {
        for (boolean read : new boolean[] { false, true }) {
            MemorySnapshots rows = new MemorySnapshots();
            rows.failRead = read;
            rows.failWrite = !read;
            try (Fixture f = new Fixture(engine(), new MemoryEvents(history()), rows)) {
                assertThrows(IllegalStateException.class, f::recover);
                assertEquals(TradingEngineService.RecoveryState.FAILED, f.engine.getRecoveryState());
                assertFalse(f.engine.readyStarted);
            }
        }
    }

    /** 周期保存只在进度变化时执行；失败后不推进保存位置，下轮会重试。 */
    @Test
    void periodicSaveSkipsIdleAndRetriesFailures() {
        MemorySnapshots rows = new MemorySnapshots();
        try (Fixture f = new Fixture(engine(), new MemoryEvents(history()), rows)) {
            f.recover();
            f.snapshots.savePeriodically();
            assertEquals(1, rows.writes);
            f.engine.processMessages(List.of(deposit(9, BUYER, AssetEnum.USD, "10")));
            rows.failWrite = true;
            f.snapshots.savePeriodically();
            assertEquals(8, rows.lastSaved.sequenceId());
            assertEquals(TradingEngineService.RecoveryState.READY, f.engine.getRecoveryState());
            rows.failWrite = false;
            f.snapshots.savePeriodically();
            assertEquals(9, rows.lastSaved.sequenceId());
            assertEquals(3, rows.writes);
        }
    }

    /** 恢复时已应用到快照位置的 Kafka 重复消息不能重复充值或重发历史输出。 */
    @Test
    void duplicateMessagesAfterSnapshotAreSkipped() {
        TestEngine source = engine();
        source.replayEvents(history());
        TestEngine target = engine();
        target.beginRecovery();
        target.restoreSnapshot(SnapshotCodec.decode(SnapshotCodec.encode(source.captureSnapshot())));
        target.processMessages(history());
        assertEquals(source.captureSnapshot(), target.captureSnapshot());
        assertNoOutputs(target);
    }

    /** 并发捕获与事件执行共用锁，每份快照余额都对应自己的序号而非跨事件拼接。 */
    @Test
    void concurrentCapturesStayOnCompleteEventBoundaries() throws Exception {
        TestEngine engine = engine();
        var threads = Executors.newFixedThreadPool(2);
        try {
            // 一个线程连续应用充值事件，另一个线程并发获取快照。
            var writer = threads.submit(() -> {
                for (int i = 1; i <= 200; i++) engine.replayEvents(List.of(deposit(i, BUYER, AssetEnum.USD, "1")));
            });
            var reader = threads.submit(() -> {
                for (int i = 0; i < 200; i++) {
                    EngineSnapshot s = engine.captureSnapshot();
                    s.validateShape();
                    BigDecimal available = s.assets().stream().filter(a -> a.userId() == BUYER)
                            .map(EngineSnapshot.AssetState::available).findFirst().orElse(BigDecimal.ZERO);
                    assertEquals(0, BigDecimal.valueOf(s.sequenceId()).compareTo(available));
                }
            });
            writer.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }
    }

    /** 快照加载后剩余历史断链仍然失败，不能让快照绕过第二阶段的顺序检查。 */
    @Test
    void missingIncrementalHistoryFailsClosed() {
        TestEngine source = engine();
        source.replayEvents(history().subList(0, 3));
        MemorySnapshots rows = new MemorySnapshots();
        rows.rows.add(SnapshotCodec.encode(source.captureSnapshot()));
        try (Fixture f = new Fixture(engine(), new MemoryEvents(List.of(history().get(5))), rows)) {
            assertThrows(IllegalStateException.class, f::recover);
            assertFalse(f.engine.readyStarted);
        }
    }

    /** 构造部分成交、撤单、完全成交和剩余买单的连续历史。 */
    private List<AbstractEvent> history() {
        return List.of(deposit(1, BUYER, AssetEnum.USD, "1000"), deposit(2, SELLER, AssetEnum.BTC, "3"),
                order(3, SELLER, Direction.SELL, "100", "0.5"), order(4, BUYER, Direction.BUY, "105", "0.1"),
                cancel(5, SELLER, id(3)), order(6, SELLER, Direction.SELL, "110", "0.2"),
                order(7, BUYER, Direction.BUY, "115", "0.2"), order(8, BUYER, Direction.BUY, "50", "0.4"));
    }

    /** 创建真实内存组件，替代实时启动方法以防止访问用户环境。 */
    private TestEngine engine() {
        TestEngine engine = new TestEngine();
        engine.zoneId = ZoneId.of("UTC");
        engine.assetService = new AssetService();
        engine.orderService = new OrderService(engine.assetService);
        engine.matchEngine = new MatchEngine();
        engine.clearingService = new ClearingService(engine.assetService, engine.orderService);
        return engine;
    }

    /** 为固定年月的事件计算真实订单 ID。 */
    private long id(long seq) { return seq * 10000 + 202601; }

    /** 设定统一的历史链与时间。 */
    private <T extends AbstractEvent> T identify(T e, long seq) {
        e.sequenceId = seq; e.previousId = seq - 1; e.createdAt = TIME + seq; return e;
    }

    /** 创建负债账户充值，不直接改变内存资产。 */
    private TransferEvent deposit(long seq, long user, AssetEnum asset, String amount) {
        TransferEvent e = identify(new TransferEvent(), seq);
        e.fromUserId = 1L; e.toUserId = user; e.asset = asset; e.amount = new BigDecimal(amount); return e;
    }

    /** 创建限价订单，实际冻结与清算交给引擎。 */
    private OrderRequestEvent order(long seq, long user, Direction direction, String price, String quantity) {
        OrderRequestEvent e = identify(new OrderRequestEvent(), seq);
        e.userId = user; e.direction = direction; e.price = new BigDecimal(price); e.quantity = new BigDecimal(quantity); return e;
    }

    /** 创建指定活动订单的撤单事件。 */
    private OrderCancelEvent cancel(long seq, long user, long id) {
        OrderCancelEvent e = identify(new OrderCancelEvent(), seq); e.userId = user; e.refOrderId = id; return e;
    }

    /** 检查所有外部输出队列为空，快照及重放不安排重复写入或消息。 */
    private void assertNoOutputs(TradingEngineService engine) {
        for (String name : List.of("orderQueue", "matchQueue", "tickQueue", "notificationQueue", "apiResultQueue"))
            assertTrue(((Queue<?>) ReflectionTestUtils.getField(engine, name)).isEmpty(), name);
    }

    private static class TestEngine extends TradingEngineService {
        boolean readyStarted;
        /** 仅测试替身标记就绪，不启动 Redis、Kafka 或历史输出线程。 */
        @Override
        public synchronized void startAfterRecovery() {
            readyStarted = true;
            ReflectionTestUtils.setField(this, "recoveryState", RecoveryState.READY);
        }
    }

    private static class MemoryEvents extends StoreService {
        final List<AbstractEvent> events;
        final List<Long> cursors = new ArrayList<>();
        /** 保存完整或故意断链的历史，用于恢复测试。 */
        MemoryEvents(List<AbstractEvent> events) { this.events = events; }
        /** 模拟开始恢复时固定的事件上界。 */
        @Override
        public long getLatestEventSequenceId() { return events.isEmpty() ? 0 : events.get(events.size() - 1).sequenceId; }
        /** 按真实分页条件返回历史，记录是否跳过了快照之前的事件。 */
        @Override
        public List<AbstractEvent> loadEventsFromDb(long last, long upper, int size) {
            cursors.add(last);
            return events.stream().filter(e -> e.sequenceId > last && e.sequenceId <= upper).limit(size).toList();
        }
    }

    private static class MemorySnapshots extends SnapshotStore {
        final List<SnapshotRecord> rows = new ArrayList<>();
        SnapshotRecord lastSaved;
        int writes;
        boolean failRead;
        boolean failWrite;
        /** 只构造 ORM 元数据，不提供数据库连接；真实 SQL 另有测试。 */
        MemorySnapshots() { super(new DbTemplate(new JdbcTemplate())); }
        /** 最新候选优先，排除领先历史的记录，模拟缺表失败。 */
        @Override
        public List<SnapshotRecord> findCandidates(long upper) {
            if (failRead) throw new IllegalStateException("模拟缺表或数据库不可用");
            return rows.stream().filter(r -> r.sequenceId() <= upper)
                    .sorted(java.util.Comparator.comparingLong(SnapshotRecord::sequenceId).reversed()).limit(5).toList();
        }
        /** 记录持久化尝试；失败不会更新成功记录。 */
        @Override
        public void save(SnapshotRecord record) {
            writes++;
            if (failWrite) throw new IllegalStateException("模拟写入失败");
            rows.add(record); lastSaved = record;
        }
    }

    private static class Fixture implements AutoCloseable {
        final TestEngine engine;
        final MemoryEvents events;
        final SnapshotService snapshots;
        /** 装配真实快照服务与恢复协调器，不模拟捕获、校验、编解码逻辑。 */
        Fixture(TestEngine engine, MemoryEvents events, MemorySnapshots rows) {
            this.engine = engine; this.events = events; this.snapshots = new SnapshotService(engine, rows);
        }
        /** 执行实际启动恢复路径。 */
        void recover() { new EngineRecoveryService(engine, events, snapshots).run(null); }
        /** 关闭测试启动的周期任务，不执行外部资源操作。 */
        @Override
        public void close() { snapshots.stop(); engine.destroy(); }
    }
}
