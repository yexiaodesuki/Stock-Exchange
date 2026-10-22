/* 真实 Vert.x HTTP/WebSocket 回归：随机端口，不连接现有 Redis，不发送业务通知。 */
package com.itranswarp.exchange.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.Vertx;
import static org.junit.jupiter.api.Assertions.*;

class PushMonitoringTest {
    /** 使用真实 Push YAML 和 NONE 启动方式验证 Bean 与健康分组，不加载 Redis 或业务监听。 */
    @Test void nonWebBootLoadsActualConfiguration() throws Exception {
        var application = new org.springframework.boot.SpringApplication(NoBusinessApplication.class);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        try (var context = application.run("--spring.config.location=file:../config-repo/application.yml,file:../config-repo/push.yml",
                "--spring.application.name=push", "--spring.cloud.config.enabled=false", "--spring.cloud.config.import-check.enabled=false",
                "--management.server.port=0", "--management.server.address=127.0.0.1")) {
            var server = context.getBean(PushManagementServer.class);
            var monitor = context.getBean(PushMonitoringService.class);
            assertEquals(503, get(server.port(), "/actuator/health/readiness").statusCode());
            monitor.listening(true); monitor.connected(true); monitor.subscribed(true);
            assertEquals(200, get(server.port(), "/actuator/health/readiness").statusCode());
            assertTrue(get(server.port(), "/actuator/prometheus").body().contains("application=\"push\""));
        }
    }

    /** 依赖未订阅返回 503，订阅成功返回 200，断连重新失败但 liveness 不失败。 */
    @Test void managementHealthAndExposure() throws Exception {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var monitor = new PushMonitoringService(registry);
        var server = new PushManagementServer(monitor, registry, "127.0.0.1", 0);
        try {
            server.start();
            assertEquals(503, get(server.port(), "/actuator/health/readiness").statusCode());
            monitor.listening(true); monitor.connected(true); monitor.subscribed(true);
            assertEquals(200, get(server.port(), "/actuator/health/readiness").statusCode());
            monitor.connected(false);
            assertEquals(503, get(server.port(), "/actuator/health/readiness").statusCode());
            assertEquals(200, get(server.port(), "/actuator/health/liveness").statusCode());
            assertTrue(get(server.port(), "/actuator/prometheus").body().contains("exchange_push_connections"));
            assertEquals(404, get(server.port(), "/actuator/env").statusCode());
            assertEquals(404, get(server.port(), "/actuator/heapdump").statusCode());
        } finally { server.stop(); registry.close(); }
    }

    /** 实际建立匿名 WebSocket 并接收广播，验证连接、写入完成和关闭后的计数。 */
    @Test void websocketConnectionsAndBroadcast() throws Exception {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var monitor = new PushMonitoringService(registry);
        var push = new PushVerticle("isolated-test-key", 0, monitor);
        Vertx vertx = Vertx.vertx();
        var listener = new Listener();
        WebSocket socket = null;
        WebSocket privateSocket = null;
        try {
            monitor.connected(true); monitor.subscribed(true);
            vertx.deployVerticle(push).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!monitor.ready() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(monitor.ready());
            socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(URI.create("ws://127.0.0.1:" + push.port() + "/notification"), listener).get(5, TimeUnit.SECONDS);
            assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains("connected"));
            assertEquals(1, registry.get("exchange.push.connections").gauge().value());
            push.broadcast("{\"type\":\"monitoring_test\",\"userId\":null}");
            assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains("monitoring_test"));
            // 客户端收到消息与服务端 Future 回调不是同一时刻，限时等待真实完成计数。
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (registry.find("exchange.push.operations").tags("stage", "write", "result", "completed").counter() == null
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, registry.get("exchange.push.operations").tags("stage", "write", "result", "completed").counter().count());
            // 私人路由仍只发给令牌中的目标用户，不能因为监控改动广播给匿名连接。
            var privateListener = new Listener();
            String token = new com.itranswarp.exchange.bean.AuthToken(77771L, System.currentTimeMillis() + 60000)
                    .toSecureString("isolated-test-key");
            privateSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + push.port() + "/notification?token=" + token), privateListener).get(5, TimeUnit.SECONDS);
            assertTrue(privateListener.messages.poll(5, TimeUnit.SECONDS).contains("77771"));
            push.broadcast("{\"type\":\"private_monitoring_test\",\"userId\":77771}");
            assertTrue(privateListener.messages.poll(5, TimeUnit.SECONDS).contains("private_monitoring_test"));
            assertNull(listener.messages.poll(250, TimeUnit.MILLISECONDS));
            privateSocket.sendClose(WebSocket.NORMAL_CLOSURE, "private test done").get(3, TimeUnit.SECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test done").get(3, TimeUnit.SECONDS);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (registry.get("exchange.push.connections").gauge().value() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, registry.get("exchange.push.connections").gauge().value());
        } finally {
            if (socket != null) socket.abort();
            if (privateSocket != null) privateSocket.abort();
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            registry.close();
        }
    }

    /** 限时访问仅在本机随机端口上的管理接口。 */
    private HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static class Listener implements WebSocket.Listener {
        final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        /** 每次只请求下一条消息，完整消息加入测试队列。 */
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) { messages.add(text.toString()); text.setLength(0); }
            socket.request(1); return null;
        }
    }

    /** 自动配置保留 NONE 的实际注册表与健康分组，只排除真实数据源，不扫描业务。 */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
    @org.springframework.context.annotation.Import({ PushMonitoringService.class, PushManagementServer.class })
    static class NoBusinessApplication { }
}
