/* 应用本地就绪：外部依赖状态独立展示，不把共享依赖故障变为 liveness 故障。 */
package com.itranswarp.exchange.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component("applicationHealthIndicator")
public class ApplicationReadiness implements HealthIndicator {
    private volatile boolean started;
    private final PipelineMonitoring monitoring;
    private final String[] required;

    /** 配置只列本应用必需的本地组件，不连接外部服务。 */
    public ApplicationReadiness(PipelineMonitoring monitoring,
            @Value("${exchange.monitoring.required-components:}") String components) {
        this.monitoring = monitoring;
        this.required = components.isBlank() ? new String[0] : components.split(",");
        monitoring.component("application", this::ready);
    }

    /** 所有启动 runner 成功后才允许本地就绪。 */
    @EventListener(ApplicationReadyEvent.class)
    public void started() { started = true; }

    /** 关闭开始即撤销就绪，避免停机仍报告正常。 */
    @EventListener(ContextClosedEvent.class)
    public void stopped() { started = false; }

    /** 读取已缓存的本地状态，不进行依赖探测。 */
    public boolean ready() {
        if (!started) return false;
        for (String component : required) if (!monitoring.componentReady(component.trim())) return false;
        return true;
    }

    /** 恢复或组件未运行时返回 503 对应的不可用状态。 */
    @Override public Health health() { return ready() ? Health.up().build() : Health.outOfService().build(); }
}
