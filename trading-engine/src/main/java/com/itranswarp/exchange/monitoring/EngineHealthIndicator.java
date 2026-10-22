/* 引擎健康检查：只读轻量状态，不查资产、不持有引擎锁；运行依赖的持续健康另行监测。 */
package com.itranswarp.exchange.monitoring;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("engineHealthIndicator")
public class EngineHealthIndicator implements HealthIndicator {
    private final EngineMonitoringService monitoring;

    /** 注入已记录状态，不依赖业务查询接口或网络探测。 */
    public EngineHealthIndicator(EngineMonitoringService monitoring) { this.monitoring = monitoring; }

    /** 仅完整就绪且输出线程存活时 UP；未就绪返回 OUT_OF_SERVICE，致命失败返回 DOWN。 */
    @Override
    public Health health() {
        if (monitoring.isReady()) return Health.up().build();
        if (monitoring.state().equals("FAILED")) return Health.down().build();
        return Health.outOfService().build();
    }
}
