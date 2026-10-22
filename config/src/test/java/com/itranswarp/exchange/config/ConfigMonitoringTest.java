/* 配置中心指标回归：只发布本地生命周期事件，不启动配置服务或外部依赖。 */
package com.itranswarp.exchange.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;

class ConfigMonitoringTest {
    /** 启动前、接受请求和停止接受请求时，指标必须跟随 Boot 实际状态变化。 */
    @Test void lifecycleGauge() {
        var registry = new SimpleMeterRegistry();
        var availability = new ApplicationAvailabilityBean();
        try {
            var gauge = new ConfigMonitoringConfiguration().configReadinessGauge(registry, availability);
            assertEquals(0, gauge.value());
            availability.onApplicationEvent(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
            assertEquals(1, gauge.value());
            availability.onApplicationEvent(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
            assertEquals(0, gauge.value());
        } finally { registry.close(); }
    }
}
