/* Push 本地状态与固定维度指标：连接数含匿名连接，写入完成不等于浏览器已处理。 */
package com.itranswarp.exchange.push;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component("applicationHealthIndicator")
public class PushMonitoringService implements org.springframework.boot.actuate.health.HealthIndicator {
    private final MeterRegistry registry;
    private final AtomicInteger connections = new AtomicInteger();
    private volatile boolean httpListening;
    private volatile boolean connected;
    private volatile boolean subscribed;
    private volatile boolean stopping;

    /** 绑定本地状态，标签不包含用户、Token 或 WebSocket 标识。 */
    public PushMonitoringService(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("exchange.push.connections", connections, AtomicInteger::get).register(registry);
        Gauge.builder("exchange.push.redis.connected", this, m -> m.connected ? 1 : 0).register(registry);
        Gauge.builder("exchange.push.redis.subscribed", this, m -> m.subscribed ? 1 : 0).register(registry);
        Gauge.builder("exchange.push.http.listening", this, m -> m.httpListening ? 1 : 0).register(registry);
        Gauge.builder("exchange.component.ready", this, m -> m.ready() ? 1 : 0).tag("component", "application").register(registry);
    }

    /** 必须业务监听和真实 Redis 订阅都成功；停止阶段立即不可用。 */
    public boolean ready() { return !stopping && httpListening && connected && subscribed; }

    /** 监听成功和停止时分别发布业务端口状态。 */
    public void listening(boolean value) { httpListening = value; }

    /** 连接丢失同时撤销订阅成功状态，不能沿用启动时的成功。 */
    public void connected(boolean value) { connected = value; if (!value) subscribed = false; }

    /** 只有实际 SUBSCRIBE 返回成功才设置成功。 */
    public void subscribed(boolean value) { subscribed = value; }

    /** 由注册映射的首次加入触发，避免重复注册导致计数膨胀。 */
    public void opened() { connections.incrementAndGet(); count("connection", "opened"); }

    /** 只由实际映射移除触发，异常和关闭竞态不重复减少。 */
    public void closed() { connections.updateAndGet(n -> Math.max(0, n - 1)); count("connection", "closed"); }

    /** 固定阶段和结果观测，注册表异常不影响已有 WebSocket 处理。 */
    public void count(String stage, String result) {
        try { registry.counter("exchange.push.operations", "stage", stage, "result", result).increment(); }
        catch (RuntimeException ignored) { /* 不以监控异常覆盖推送结果。 */ }
    }

    /** 统一健康响应不返回连接配置、用户或异常信息。 */
    public String healthJson() { return ready() ? "{\"status\":\"UP\"}" : "{\"status\":\"OUT_OF_SERVICE\"}"; }

    /** 非 Web Boot 仍校验全局健康分组，使用同一真实状态提供 application 健康组件。 */
    @Override public org.springframework.boot.actuate.health.Health health() {
        return ready() ? org.springframework.boot.actuate.health.Health.up().build()
                : org.springframework.boot.actuate.health.Health.outOfService().build();
    }

    /** 关闭应用时撤销就绪。 */
    public void stopping() { stopping = true; connected(false); listening(false); }
}
