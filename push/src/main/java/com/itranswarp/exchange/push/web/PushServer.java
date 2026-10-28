/* 证券 WebSocket：一次性票据认证，行情按证券、私人提示按用户分发，断连后要求重新同步。 */
package com.itranswarp.exchange.push.web;

import com.fasterxml.jackson.databind.*;
import com.itranswarp.exchange.push.PushService;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.*;
import io.vertx.core.*;
import io.vertx.core.http.*;
import java.util.*;
import java.util.concurrent.*;

public final class PushServer implements AutoCloseable {
  private final Vertx vertx = Vertx.vertx();
  private final MarketCache cache;
  private final int port;
  private final Set<String> origins;
  private final PushVerticle sessions;
  private final PushService service;
  private HttpServer server;
  private String bindAddress = "127.0.0.1";

  /** Origin 必须显式允许，不能用通配符信任其他网站。 */
  public PushServer(MarketCache cache, SecurityCatalog catalog, int port, String origins) {
    this.cache = cache;
    this.port = port;
    this.origins = Set.of(origins.split(","));
    sessions = new PushVerticle(vertx, cache, catalog);
    service = new PushService(cache, sessions);
  }

  /** Docker 使用容器监听地址；默认构造器仍仅监听回环供本机测试使用。 */
  public PushServer(
      MarketCache cache, SecurityCatalog catalog, int port, String origins, String address) {
    this(cache, catalog, port, origins);
    this.bindAddress = address;
  }

  /** 启动本机监听，Redis 订阅在专属工作线程重试而非阻塞 Vert.x 事件循环。 */
  public void start() {
    server =
        vertx.createHttpServer(
            new HttpServerOptions()
                .setMaxWebSocketFrameSize(8192)
                .setMaxWebSocketMessageSize(8192));
    server.requestHandler(
        request -> {
          if (request.path().equals("/actuator/health")) {
            request
                .response()
                .setStatusCode(service.ready() ? 200 : 503)
                .putHeader("Content-Type", "application/json")
                .end(service.ready() ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}");
            return;
          }
          if (!request.path().equals("/ws")) {
            request.response().setStatusCode(404).end();
            return;
          }
          if (!service.ready()) {
            request.response().setStatusCode(503).end();
            return;
          }
          if (!origins.contains(request.getHeader("Origin"))) {
            request.response().setStatusCode(403).end();
            return;
          }
          // 异步验证前暂停请求流，否则空 GET 请求可能先结束而无法再升级。
          request.pause();
          // Redis 票据读取移到工作线程；只有票据成功才执行 WebSocket 升级。
          vertx
              .<MarketCache.Ticket>executeBlocking(
                  promise -> {
                    try {
                      promise.complete(cache.consumeTicket(request.getParam("ticket")));
                    } catch (Exception invalid) {
                      promise.fail(invalid);
                    }
                  })
              .onSuccess(
                  identity -> {
                    request
                        .toWebSocket()
                        .onSuccess(socket -> sessions.connected(socket, identity))
                        .onFailure(ignored -> {});
                    request.resume();
                  })
              .onFailure(
                  invalid -> {
                    request.resume();
                    if (!request.response().ended()) request.response().setStatusCode(401).end();
                  });
        });
    try {
      server
          .listen(port, bindAddress)
          .toCompletionStage()
          .toCompletableFuture()
          .get(10, TimeUnit.SECONDS);
    } catch (Exception failed) {
      throw new IllegalStateException("推送端口启动失败", failed);
    }
    service.start();
    sessions.startHeartbeat();
  }

  /** 就绪依赖 Redis 实际订阅确认。 */
  public boolean ready() {
    return service.ready();
  }

  /** 仅释放本实例资源，不删除 Redis 数据。 */
  @Override
  public synchronized void close() {
    service.stop();
    sessions.close();
    service.close();
    if (server != null) server.close();
    vertx.close();
  }
}
