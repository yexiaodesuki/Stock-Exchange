/* 固定维度业务指标：不记录账户、订单、请求地址或异常文本；监控失败不覆盖业务结果。 */
package com.itranswarp.exchange.monitoring;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class PipelineMonitoring {
    private MeterRegistry registry;
    private final Map<String, BooleanSupplier> components = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> positions = new ConcurrentHashMap<>();

    /** 手工构造的原有业务测试不需要注册表。 */
    public PipelineMonitoring() { }

    /** Spring 环境复用本应用注册表，不引入全局静态注册表。 */
    @Autowired
    public PipelineMonitoring(ObjectProvider<MeterRegistry> registry) { this.registry = registry.getIfAvailable(); }

    /** 为隔离测试绑定本地注册表，不执行网络调用。 */
    public PipelineMonitoring(MeterRegistry registry) { this.registry = registry; }

    /** 固定阶段与结果统计；所有调用方只能传代码中定义的常量。 */
    public void count(String stage, String result) { count(stage, result, 1); }

    /** 按明确单位增加数量；注册表异常不能改变消息或交易路径。 */
    public void count(String stage, String result, long amount) {
        try { if (registry != null && amount > 0) registry.counter("exchange.pipeline.operations", "stage", stage, "result", result).increment(amount); }
        catch (RuntimeException ignored) { /* 指标不是业务提交。 */ }
    }

    /** 单调时钟记录完整调用耗时，不包含用户信息。 */
    public void duration(String stage, long started) {
        try { if (registry != null) registry.timer("exchange.pipeline.duration", "stage", stage)
                .record(Math.max(0, System.nanoTime() - started), TimeUnit.NANOSECONDS); }
        catch (RuntimeException ignored) { /* 保留原始业务结果。 */ }
    }

    /** 成功返回后更新独立进度；不复用业务处理中提前赋值的位置。 */
    public void progress(String stage, long sequence) {
        try { positions.computeIfAbsent(stage, key -> {
            AtomicLong position = new AtomicLong();
            if (registry != null) Gauge.builder("exchange.pipeline.completed.sequence", position, AtomicLong::get).tag("stage", key).register(registry);
            return position;
        }).set(sequence); }
        catch (RuntimeException ignored) { /* 指标注册失败不能使已经提交的业务失败。 */ }
    }

    /** 绑定固定组件的只读状态，读取时不能访问网络或取得业务锁。 */
    public void component(String name, BooleanSupplier state) {
        boolean first = !components.containsKey(name);
        components.put(name, state);
        try { if (first && registry != null) Gauge.builder("exchange.component.ready", this, m -> m.componentReady(name) ? 1 : 0).tag("component", name).register(registry); }
        catch (RuntimeException ignored) { /* 组件状态仍保存，注册失败不能创建业务副作用。 */ }
    }

    /** 未初始化组件或读取失败都不能报告正常。 */
    public boolean componentReady(String name) {
        try { BooleanSupplier state = components.get(name); return state != null && state.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    /** 为 API 等本地状态绑定轻量数值，不遍历用户数据。 */
    public void gauge(String name, java.util.function.ToDoubleFunction<PipelineMonitoring> read) {
        try { if (registry != null) Gauge.builder(name, this, read).register(registry); }
        catch (RuntimeException ignored) { /* 不覆盖原始业务异常。 */ }
    }
}
