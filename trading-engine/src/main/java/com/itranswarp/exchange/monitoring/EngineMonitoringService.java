/* 引擎指标状态：只记录固定维度、序号和耗时；抓取不加引擎锁、不查数据库、不暴露账户信息。 */
package com.itranswarp.exchange.monitoring;

import java.util.EnumMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class EngineMonitoringService {
    /** 输出线程维度固定，不能使用用户、订单、异常文本作为指标标签。 */
    public enum Output { TICK, NOTIFY, API_RESULT, ORDERBOOK, DB }
    private final Map<Output, Thread> workers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Output, Counter> outputFailures = new EnumMap<>(Output.class);
    private final Map<String, Counter> events = new java.util.HashMap<>();
    private final Map<String, Timer> eventTimers = new java.util.HashMap<>();
    private final Map<String, Counter> snapshotResults = new java.util.HashMap<>();
    private final Map<String, Counter> recoveryResults = new java.util.HashMap<>();
    private MeterRegistry registry;
    private Timer recoveryTimer;
    private Timer snapshotTimer;
    private Counter tickSendCallsReturned;
    private volatile String state = "RECOVERING";
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong recoveryTarget = new AtomicLong();
    private final AtomicLong snapshotSequence = new AtomicLong(-1);
    private final AtomicLong snapshotTimestamp = new AtomicLong();
    private volatile boolean snapshotSaving;
    private volatile boolean workersExpected;

    /** 未经 Spring 构造的引擎测试只记录状态，不依赖外部监控服务或全局注册表。 */
    public EngineMonitoringService() { }

    /** 注册固定集合的指标；Spring 注入本机注册表，记录过程不会发送网络请求。 */
    @Autowired
    public EngineMonitoringService(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("exchange.engine.ready", this, m -> m.isReady() ? 1 : 0).register(registry);
        Gauge.builder("exchange.engine.applied.sequence", applied, AtomicLong::get).register(registry);
        Gauge.builder("exchange.recovery.target.sequence", recoveryTarget, AtomicLong::get).register(registry);
        for (String s : new String[] { "RECOVERING", "READY", "FAILED", "STOPPED" }) {
            Gauge.builder("exchange.engine.state", this, m -> m.state.equals(s) ? 1 : 0).tag("state", s).register(registry);
        }
        Gauge.builder("exchange.snapshot.saved.sequence", snapshotSequence, AtomicLong::get).register(registry);
        Gauge.builder("exchange.snapshot.last.success.timestamp", snapshotTimestamp, n -> n.get() / 1000.0).register(registry);
        Gauge.builder("exchange.snapshot.saving", this, m -> m.snapshotSaving ? 1 : 0).register(registry);
        Gauge.builder("exchange.snapshot.unsaved.sequence.distance", this,
                m -> Math.max(0, m.applied.get() - m.snapshotSequence.get())).register(registry);
        for (String result : new String[] { "success", "failure" }) {
            snapshotResults.put(result, registry.counter("exchange.snapshot.save", "result", result));
            recoveryResults.put(result, registry.counter("exchange.recovery.completed", "result", result));
        }
        for (String mode : new String[] { "live", "replay" }) {
            eventTimers.put(mode, Timer.builder("exchange.event.duration").tag("mode", mode).register(registry));
            for (String result : new String[] { "applied", "duplicate", "failure" }) {
                events.put(mode + ":" + result, registry.counter("exchange.events", "mode", mode, "result", result));
            }
        }
        for (Output output : Output.values()) {
            String label = output.name().toLowerCase(java.util.Locale.ROOT);
            outputFailures.put(output, registry.counter("exchange.output.failures", "worker", label));
            Gauge.builder("exchange.output.worker.alive", this, m -> m.workerAlive(output) ? 1 : 0)
                    .tag("worker", label).register(registry);
        }
        Gauge.builder("exchange.output.workers.expected", this, m -> m.workersExpected ? 1 : 0).register(registry);
        recoveryTimer = registry.timer("exchange.recovery.duration");
        snapshotTimer = registry.timer("exchange.snapshot.save.duration");
        tickSendCallsReturned = registry.counter("exchange.tick.send.calls.returned");
    }

    /** 绑定 O(1) 计数队列；任务单位为队列元素，数据库队列元素可能是一批记录。 */
    public void bindQueue(String name, Queue<?> queue) {
        if (registry != null) Gauge.builder("exchange.output.pending", queue, Queue::size).tag("queue", name).register(registry);
    }

    /** 注册线程引用，抓取时只读取 isAlive，不等待工作线程或引擎锁。 */
    public void bindWorker(Output output, Thread thread) { workers.put(output, thread); }

    /** 读取实际线程存活；尚未创建、已经退出或停止后的线程不报告存活。 */
    public boolean workerAlive(Output output) {
        Thread thread = workers.get(output);
        return thread != null && thread.isAlive();
    }

    /** 开放实时服务后预期所有输出线程存活，停机前关闭预期以避免误报。 */
    public void expectWorkers(boolean expected) { workersExpected = expected; }

    /** 返回就绪：恢复已完成，且预期的输出线程没有退出；不证明外部依赖持续正常。 */
    public boolean isReady() {
        return state.equals("READY") && (!workersExpected || java.util.Arrays.stream(Output.values()).allMatch(this::workerAlive));
    }

    /** 更新生命周期，不改变引擎业务门控；指标不包含任何账户或订单数据。 */
    public void state(String value) { state = value; }

    /** 获取监控生命周期供健康检查使用，不读取可变资产或订单集合。 */
    public String state() { return state; }

    /** 在事件完整执行或有效快照安装后更新可见进度，不把数据库最大值当作已执行位置。 */
    public void applied(long sequence) { applied.set(sequence); }

    /** 开始恢复并返回单调时钟起点，目标上界稍后从已提交事件确定。 */
    public long recoveryStarted() { state("RECOVERING"); return System.nanoTime(); }

    /** 记录固定历史上界，序号差距不是事件条数或 Kafka 消费积压。 */
    public void recoveryTarget(long sequence) { recoveryTarget.set(sequence); }

    /** 记录恢复完成结果和耗时，不在异常日志中重复暴露历史事件内容。 */
    public void recoveryFinished(long start, boolean success) {
        record(recoveryResults.get(success ? "success" : "failure"));
        duration(recoveryTimer, start);
    }

    /** 记录实际执行、重复或异常，实时和重放单独统计；业务拒绝仍是完整应用的事件。 */
    public void event(String mode, String result, long start) {
        record(events.get(mode + ":" + result));
        duration(eventTimers.get(mode), start);
    }

    /** 标记包括捕获和编码在内的保存尝试开始；空闲跳过不调用此入口。 */
    public long snapshotStarted() { snapshotSaving = true; return System.nanoTime(); }

    /** 只有持久化返回成功才更新位置和时间；相同内容幂等检查成功也算保存成功。 */
    public void snapshotFinished(long start, long sequence, boolean success) {
        if (success) {
            snapshotSequence.set(sequence);
            snapshotTimestamp.set(System.currentTimeMillis());
        }
        snapshotSaving = false;
        record(snapshotResults.get(success ? "success" : "failure"));
        duration(snapshotTimer, start);
    }

    /** 记录工作线程未处理异常，保持原有退出行为，不自动重试或恢复资产。 */
    public void outputFailed(Output output) { record(outputFailures.get(output)); }

    /** 一批 Tick 发送调用正常返回一次；不表示消息条数、broker 确认或行情消费成功。 */
    public void tickSendReturned() { record(tickSendCallsReturned); }

    /** 计数器故障不改变交易成功或失败结果；未注册测试实例不产生指标副作用。 */
    private void record(Counter counter) {
        try { if (counter != null) counter.increment(); } catch (RuntimeException ignored) { /* 监控不参与业务提交。 */ }
    }

    /** 使用单调时钟统计耗时，注册表故障不覆盖原始业务异常。 */
    private void duration(Timer timer, long start) {
        try { if (timer != null) timer.record(Math.max(0, System.nanoTime() - start), TimeUnit.NANOSECONDS); }
        catch (RuntimeException ignored) { /* 监控不阻塞恢复或事件处理。 */ }
    }
}
