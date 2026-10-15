/* 独立 HTTP 测试：真实启动 Actuator，不启动交易引擎或连接业务数据库。 */
package com.itranswarp.exchange.monitoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringHttpTest {
    /** 验证独立管理端口的状态码、指标格式，以及敏感管理接口未暴露。 */
    @Test void managementEndpoints() throws Exception {
        var application = new SpringApplication(TestApplication.class);
        application.setDefaultProperties(Map.of(
                "spring.config.location", "optional:classpath:/monitoring-http-test.yml",
                "spring.cloud.config.enabled", "false",
                "spring.cloud.config.import-check.enabled", "false",
                "server.port", "0",
                "management.server.port", "0",
                "management.endpoints.web.exposure.include", "health,prometheus",
                "management.endpoint.health.probes.enabled", "true",
                "management.endpoint.health.group.liveness.include", "livenessState",
                "management.endpoint.health.group.readiness.include", "readinessState,engine"));
        try (var context = application.run()) {
            int port = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
            var monitor = context.getBean(EngineMonitoringService.class);
            assertEquals(200, get(port, "/actuator/health/liveness").statusCode());
            assertEquals(503, get(port, "/actuator/health/readiness").statusCode());
            monitor.state("READY");
            assertEquals(200, get(port, "/actuator/health/readiness").statusCode());
            monitor.state("FAILED");
            assertEquals(503, get(port, "/actuator/health/readiness").statusCode());
            var metrics = get(port, "/actuator/prometheus");
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("exchange_engine_ready"));
            assertFalse(metrics.body().contains("userId="));
            assertEquals(404, get(port, "/actuator/env").statusCode());
            assertEquals(404, get(port, "/actuator/heapdump").statusCode());
        }
    }

    /** 使用限时本地请求，端口来自测试随机分配，不依赖固定运行环境。 */
    private HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 仅加载自动配置与监控组件，排除需要真实数据库的数据源。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({ EngineMonitoringService.class, EngineHealthIndicator.class })
    static class TestApplication { }
}
