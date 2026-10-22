/* Push 的独立 Vert.x 管理监听：保留 WebApplicationType.NONE，只开放健康与指标。 */
package com.itranswarp.exchange.push;

import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;

@Component
public class PushManagementServer {
    private final PushMonitoringService monitoring;
    private final PrometheusMeterRegistry registry;
    private final String address;
    private final int configuredPort;
    private Vertx vertx;
    private HttpServer server;

    /** 单独使用配置管理端口，不改 8006 的 WebSocket 地址。 */
    public PushManagementServer(PushMonitoringService monitoring, PrometheusMeterRegistry registry,
            @Value("${management.server.address:0.0.0.0}") String address,
            @Value("${management.server.port:18006}") int port) {
        this.monitoring = monitoring; this.registry = registry; this.address = address; this.configuredPort = port;
    }

    /** 等待管理监听建立；失败关闭资源并阻止假装正常启动。 */
    @PostConstruct public void start() throws Exception {
        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        // 健康处理仅读本地状态，依赖断开不把 liveness 置为失败。
        router.get("/actuator/health/liveness").handler(ctx -> ctx.response().putHeader("Content-Type", "application/json").end("{\"status\":\"UP\"}"));
        for (String path : new String[] { "/actuator/health", "/actuator/health/readiness" }) {
            router.get(path).handler(ctx -> ctx.response().setStatusCode(monitoring.ready() ? 200 : 503)
                    .putHeader("Content-Type", "application/json").end(monitoring.healthJson()));
        }
        // 抓取仅序列化内存指标，不访问 Redis 或业务锁。
        router.get("/actuator/prometheus").handler(ctx -> ctx.response()
                .putHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8").end(registry.scrape()));
        router.route().handler(ctx -> ctx.response().setStatusCode(404).end());
        try {
            server = vertx.createHttpServer().requestHandler(router).listen(configuredPort, address)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) { vertx.close(); throw e; }
    }

    /** 返回绑定后的随机或配置端口，仅用于测试与诊断。 */
    public int port() { return server.actualPort(); }

    /** 关闭独立管理事件循环，不关闭业务 Vert.x 或清理外部数据。 */
    @PreDestroy public void stop() throws Exception {
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
