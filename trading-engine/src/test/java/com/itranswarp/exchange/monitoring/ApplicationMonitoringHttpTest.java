/* 多应用配置 HTTP 验证：加载真实集中配置，但仅导入监控 Bean，禁止业务消费者和数据源。 */
package com.itranswarp.exchange.monitoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationMonitoringHttpTest {
    /** 验证真实 YAML 合并后的应用标签、管理隔离和 readiness，不创建消息或读取用户表。 */
    @ParameterizedTest
    @ValueSource(strings = { "ui", "trading-api", "trading-sequencer", "quotation", "config-server" })
    void actualApplicationConfiguration(String name) throws Exception {
        String locations = name.equals("config-server") ? "file:../config/src/main/resources/application.yml"
                : "file:../config-repo/application.yml,file:../config-repo/" + name + ".yml";
        SpringApplication application = new SpringApplication(TestApplication.class);
        try (var context = application.run("--spring.config.location=" + locations,
                "--spring.application.name=" + name, "--server.port=0", "--management.server.port=0",
                "--management.server.address=127.0.0.1", "--spring.cloud.config.enabled=false",
                "--spring.cloud.config.import-check.enabled=false", "--exchange.monitoring.dependencies-enabled=false")) {
            int port = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
            int mainPort = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            var monitoring = context.getBean(PipelineMonitoring.class);
            if (name.equals("trading-api") || name.equals("trading-sequencer") || name.equals("quotation")) {
                assertEquals(503, get(port, "/actuator/health/readiness").statusCode());
            }
            for (String component : new String[] { "api_result_subscription", "sequencer", "consumer_SEQUENCE", "consumer_TICK" }) {
                monitoring.component(component, () -> true);
            }
            assertEquals(200, get(port, "/actuator/health/readiness").statusCode());
            assertEquals(200, get(port, "/actuator/health/liveness").statusCode());
            String metrics = get(port, "/actuator/prometheus").body();
            assertTrue(metrics.contains("application=\"" + name + "\""));
            assertTrue(metrics.contains("exchange_component_ready"));
            assertEquals(404, get(port, "/actuator/env").statusCode());
            assertEquals(404, get(port, "/actuator/heapdump").statusCode());
            assertEquals(404, get(mainPort, "/actuator/prometheus").statusCode());
        }
    }

    /** 限时请求随机端口上的隔离测试应用，不访问已有业务应用。 */
    private HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 不扫描业务组件，防止导入定序、引擎 runner 或 Redis 客户端。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({ PipelineMonitoring.class, ApplicationReadiness.class })
    static class TestApplication { }
}
