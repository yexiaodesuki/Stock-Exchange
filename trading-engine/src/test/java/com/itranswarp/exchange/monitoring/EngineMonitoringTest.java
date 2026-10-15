/* 监控单元测试：验证生命周期、保存失败和队列计数，不连接真实业务中间件。 */
package com.itranswarp.exchange.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;

class EngineMonitoringTest {
    /** 真实输出线程包装器必须保留异常退出行为，并把对应故障计数加一。 */
    @Test void outputThreadFailureIsObserved() throws Exception {
        var registry = new SimpleMeterRegistry();
        var monitor = new EngineMonitoringService(registry);
        var engine = new com.itranswarp.exchange.TradingEngineService();
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "monitoring", monitor);
        var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        // 模拟输出动作的未处理异常，不执行真实数据库或消息调用。
        Runnable action = () -> { throw new IllegalStateException("测试输出故障"); };
        Thread worker = org.springframework.test.util.ReflectionTestUtils.invokeMethod(engine, "newOutputThread",
                EngineMonitoringService.Output.DB, action, "test-output");
        // 测试捕获线程末端异常，以验证包装器没有吞掉原异常。
        worker.setUncaughtExceptionHandler((thread, error) -> observed.set(error));
        try {
            worker.start();
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertInstanceOf(IllegalStateException.class, observed.get());
            assertEquals(1, registry.get("exchange.output.failures").tag("worker", "db").counter().count());
            assertFalse(monitor.workerAlive(EngineMonitoringService.Output.DB));
        } finally {
            worker.interrupt();
            engine.destroy();
            registry.close();
        }
    }

    /** 并发生产消费后必须回到零，超时或中断不会留下无限运行的测试线程。 */
    @Test void concurrentPendingQueue() throws Exception {
        var queue = new PendingQueue<Integer>();
        var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // 一个生产者与一个消费者同时运行，验证计数更新与实际出队数量一致。
            var producer = threads.submit(() -> { for (int i = 0; i < 10000; i++) queue.offer(i); });
            var consumer = threads.submit(() -> {
                int count = 0;
                while (count < 10000 && !Thread.currentThread().isInterrupted()) {
                    if (queue.poll() != null) count++;
                    else Thread.yield();
                }
                return count;
            });
            producer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(10000, consumer.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, queue.size());
        } finally { threads.shutdownNow(); }
    }

    /** 恢复中不能报告就绪，失败与停止也不能返回健康。 */
    @Test void lifecycle() {
        var registry = new SimpleMeterRegistry();
        try {
            var monitor = new EngineMonitoringService(registry);
            var health = new EngineHealthIndicator(monitor);
            assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus());
            monitor.state("READY");
            assertEquals(Status.UP, health.health().getStatus());
            monitor.state("FAILED");
            assertEquals(Status.DOWN, health.health().getStatus());
            assertEquals(0, registry.get("exchange.engine.ready").gauge().value());
            monitor.state("STOPPED");
            assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus());
        } finally { registry.close(); }
    }

    /** 保存失败不能推进已持久化位置，实时和重放使用独立计数。 */
    @Test void snapshotAndEvents() {
        var registry = new SimpleMeterRegistry();
        try {
            var monitor = new EngineMonitoringService(registry);
            monitor.applied(10);
            monitor.snapshotFinished(monitor.snapshotStarted(), 8, true);
            monitor.snapshotFinished(monitor.snapshotStarted(), -1, false);
            assertEquals(8, registry.get("exchange.snapshot.saved.sequence").gauge().value());
            assertEquals(2, registry.get("exchange.snapshot.unsaved.sequence.distance").gauge().value());
            assertEquals(0, registry.get("exchange.snapshot.saving").gauge().value());
            assertEquals(1, registry.get("exchange.snapshot.save").tag("result", "failure").counter().count());
            assertEquals(2, registry.get("exchange.snapshot.save.duration").timer().count());
            monitor.event("replay", "applied", System.nanoTime());
            monitor.event("live", "duplicate", System.nanoTime());
            assertEquals(1, registry.get("exchange.events").tags("mode", "replay", "result", "applied").counter().count());
            assertEquals(0, registry.get("exchange.events").tags("mode", "live", "result", "applied").counter().count());
            monitor.tickSendReturned();
            assertEquals(1, registry.get("exchange.tick.send.calls.returned").counter().count());
        } finally { registry.close(); }
    }

    /** 预期输出线程退出后，健康检查必须识别为不可用。 */
    @Test void missingWorkerIsNotReady() {
        var monitor = new EngineMonitoringService();
        monitor.state("READY");
        monitor.expectWorkers(true);
        assertFalse(monitor.isReady());
        monitor.expectWorkers(false);
        assertTrue(monitor.isReady());
    }

    /** 队列保持先进先出，清空和失败入队不污染待处理计数。 */
    @Test void pendingQueue() {
        var queue = new PendingQueue<Integer>();
        queue.add(1);
        queue.add(2);
        assertEquals(2, queue.size());
        assertEquals(1, queue.poll());
        assertEquals(1, queue.size());
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertEquals(1, queue.size());
        queue.clear();
        assertEquals(0, queue.size());
        assertNull(queue.poll());
    }
}
