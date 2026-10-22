/* 配置中心就绪指标：复用 Boot 生命周期，不请求自身配置接口，不加载业务依赖。 */
package com.itranswarp.exchange.config;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
public class ConfigMonitoringConfiguration {
    /** 通过 Boot 接受请求状态报告配置中心就绪，不把抓取成功等同于就绪。 */
    @Bean public Gauge configReadinessGauge(MeterRegistry registry, ApplicationAvailability availability) {
        return Gauge.builder("exchange.component.ready", availability,
                state -> state.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC ? 1 : 0)
                .tag("component", "application").register(registry);
    }
}
