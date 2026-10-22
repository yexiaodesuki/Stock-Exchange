/* 公共监控回归：模拟时间、失败和本地组件，不连接现有业务容器。 */
package com.itranswarp.exchange.monitoring;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringStateTest {
    /** 未检查、失败和过期都不能报告依赖正常，时间回拨不影响单调有效期。 */
    @Test void dependencyFreshness() {
        AtomicLong clock = new AtomicLong();
        DependencyStatus status = new DependencyStatus(clock::get, 1000);
        assertFalse(status.fresh()); assertFalse(status.up());
        status.record(true);
        assertTrue(status.up());
        clock.set(1_001_000_000L);
        assertFalse(status.fresh()); assertFalse(status.up());
        status.record(false);
        assertTrue(status.fresh()); assertFalse(status.up());
        status.record(true);
        assertTrue(status.up());
    }

    /** Spring 启动完成不代表关键工作组件已运行，停止后必须撤销本地就绪。 */
    @Test void applicationReadiness() {
        var monitor = new PipelineMonitoring();
        var readiness = new ApplicationReadiness(monitor, "consumer_TICK");
        assertFalse(readiness.ready());
        readiness.started(); assertFalse(readiness.ready());
        monitor.component("consumer_TICK", () -> true);
        assertTrue(readiness.ready());
        monitor.component("consumer_TICK", () -> false);
        assertFalse(readiness.ready());
        readiness.stopped(); assertFalse(readiness.ready());
    }

    /** 探测失败记失败，重试恢复后记成功；健康查询不执行探测。 */
    @Test void probeFailureAndRecovery() {
        var registry = new SimpleMeterRegistry();
        var beans = new DefaultListableBeanFactory();
        var service = new DependencyMonitoringService(registry, beans.getBeanProvider(javax.sql.DataSource.class),
                beans.getBeanProvider(com.itranswarp.exchange.redis.RedisConfiguration.class),
                beans.getBeanProvider(org.springframework.kafka.core.KafkaAdmin.class), "mysql", 15000);
        var state = new DependencyStatus(45000);
        try {
            service.check("mysql", state, () -> { throw new IllegalStateException("隔离故障"); });
            assertFalse(state.up());
            assertEquals(1, registry.get("exchange.dependency.checks").tags("dependency", "mysql", "result", "failure").counter().count());
            service.check("mysql", state, () -> { /* 只读探测替身成功。 */ });
            assertTrue(state.up());
            assertEquals(2, registry.get("exchange.dependency.duration").tag("dependency", "mysql").timer().count());
        } finally { service.stop(); registry.close(); }
    }
}
