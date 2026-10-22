/* WebSocket 路由与结果观测：连接注册幂等，写入完成不等于浏览器消费成功。 */
package com.itranswarp.exchange.push;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.itranswarp.exchange.bean.AuthToken;
import com.itranswarp.exchange.message.NotificationMessage;
import com.itranswarp.exchange.util.JsonUtil;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;

public class PushVerticle extends AbstractVerticle {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final String hmacKey;

    private final int serverPort;

    private final PushMonitoringService monitoring;
    private HttpServer server;

    /** 用同一实例记录监听、连接和推送状态，不新增用户指标标签。 */
    PushVerticle(String hmacKey, int serverPort, PushMonitoringService monitoring) {
        this.hmacKey = hmacKey; this.serverPort = serverPort; this.monitoring = monitoring;
    }


    /**
     * All handlers.
     */
    private final Map<String, Boolean> handlersSet = new ConcurrentHashMap<>(1000);

    private final Map<String, ServerWebSocket> sockets = new ConcurrentHashMap<>();

    /**
     * userId -> set of handlers.
     */
    private final Map<Long, Set<String>> userToHandlersMap = new ConcurrentHashMap<>(1000);

    /**
     * handler -> userId.
     */
    private final Map<String, Long> handlerToUserMap = new ConcurrentHashMap<>(1000);

    /** 业务路由保持不变，原固定 UP 健康接口改为反映实际推送就绪。 */
    @Override
    public void start() {
        // 创建VertX HttpServer:
        server = vertx.createHttpServer();

        // 创建路由:
        Router router = Router.router(vertx);

        // 处理请求 GET /notification:
        router.get("/notification").handler(requestHandler -> {
            HttpServerRequest request = requestHandler.request();
            // 从token参数解析userId:
            Supplier<Long> supplier = () -> {
                String tokenStr = request.getParam("token");
                if (tokenStr != null && !tokenStr.isEmpty()) {
                    AuthToken token = AuthToken.fromSecureString(tokenStr, this.hmacKey);
                    if (!token.isExpired()) {
                        return token.userId();
                    }
                }
                return null;
            };
            final Long userId = supplier.get();
            logger.info("parse user id from token: {}", userId);
            // 将连接升级到WebSocket:
            request.toWebSocket(ar -> {
                if (ar.succeeded()) {
                    initWebSocket(ar.result(), userId);
                }
            });
        });

        // 处理请求 GET /actuator/health:
        router.get("/actuator/health").respond(
                ctx -> ctx.response().setStatusCode(monitoring.ready() ? 200 : 503)
                        .putHeader("Content-Type", "application/json").end(monitoring.healthJson()));

        // 处理其他请求:
        router.get().respond(ctx -> ctx.response().setStatusCode(404).setStatusMessage("No Route Found").end());

        server.requestHandler(router).listen(this.serverPort, result -> {
            if (result.succeeded()) {
                monitoring.listening(true);
                logger.info("Vertx started on port(s): {} (http) with context path ''", this.serverPort);
            } else {
                monitoring.listening(false);
                logger.error("Start http server failed on port " + this.serverPort, result.cause());
                vertx.close();
                System.exit(1);
            }
        });
    }

    /** 实际端口支持隔离测试随机监听，不改变配置的业务端口。 */
    int port() { return server.actualPort(); }

    /** Verticle 停止时撤销监听状态，连接关闭由 Vert.x 完成。 */
    @Override public void stop() { monitoring.listening(false); }

    /** 注册一次连接并安装关闭与错误观察，指标不使用 userId。 */
    void initWebSocket(ServerWebSocket websocket, Long userId) {
        String handlerId = websocket.textHandlerID();
        sockets.put(handlerId, websocket);
        logger.info("websocket accept userId: " + userId + ", handlerId: " + handlerId);
        // handle text message:
        websocket.textMessageHandler(str -> {
            logger.info("text message: " + str);
        });
        websocket.exceptionHandler(t -> {
            monitoring.count("connection", "failure");
            logger.error("websocket error: " + t.getMessage(), t);
        });
        // on close:
        websocket.closeHandler(e -> {
            unsubscribeClient(handlerId);
            sockets.remove(handlerId);
            unsubscribeUser(handlerId, userId);
            logger.info("websocket closed: " + handlerId);
        });
        subscribeClient(handlerId);
        subscribeUser(handlerId, userId);
        // send welcome message:
        if (userId == null) {
            websocket.writeTextMessage(
                    "{\"type\":\"status\",\"status\":\"connected\",\"message\":\"connected as anonymous user\"}");
        } else {
            websocket.writeTextMessage(
                    "{\"type\":\"status\",\"status\":\"connected\",\"message\":\"connected as user\",\"userId\":"
                            + userId + "}");
        }
    }

    /** 保留用户路由，事件总线提交和真实 socket 写入结果分开观测。 */
    public void broadcast(String text) {
        NotificationMessage message = null;
        try {
            message = JsonUtil.readJson(text, NotificationMessage.class);
        } catch (Exception e) {
            logger.error("invalid message format: {}", text);
            return;
        }
        if (message.userId == null) {
            if (logger.isInfoEnabled()) {
                logger.info("try broadcast message to all: {}", text);
            }
            // broadcast to all:
            for (String handler : this.handlersSet.keySet()) {
                writeSocket(handler, text);
            }
        } else {
            if (logger.isInfoEnabled()) {
                logger.info("try broadcast message to user {}: {}", message.userId, text);
            }
            // broadcast to single user:
            Set<String> handlers = this.userToHandlersMap.get(message.userId);
            if (handlers != null) {
                for (String handler : handlers) {
                    writeSocket(handler, text);
                }
            }
        }
    }

    /** 使用原目标集合向 socket 写入相同消息，观察 Future 完成，不承诺浏览器确认。 */
    private void writeSocket(String handler, String text) {
        ServerWebSocket socket = sockets.get(handler);
        if (socket == null) return;
        socket.writeTextMessage(text)
                .onSuccess(ignored -> monitoring.count("write", "completed"))
                .onFailure(error -> monitoring.count("write", "failure"));
    }

    /** 只有首次注册才增加连接数，并观察该 handler 的实际 socket 写入。 */
    void subscribeClient(String handlerId) {
        if (this.handlersSet.putIfAbsent(handlerId, Boolean.TRUE) == null) monitoring.opened();
    }

    /** 只有真正移除才减少连接数，防止关闭回调重复计数。 */
    void unsubscribeClient(String handlerId) {
        if (this.handlersSet.remove(handlerId) != null) monitoring.closed();
    }

    void subscribeUser(String handlerId, Long userId) {
        if (userId == null) {
            return;
        }
        handlerToUserMap.put(handlerId, userId);
        Set<String> set = userToHandlersMap.get(userId);
        if (set == null) {
            set = new HashSet<>();
            userToHandlersMap.put(userId, set);
        }
        set.add(handlerId);
        logger.info("subscribe user {} {} ok.", userId, handlerId);
    }

    void unsubscribeUser(String handlerId, Long userId) {
        if (userId == null) {
            return;
        }
        handlerToUserMap.remove(handlerId);
        Set<String> set = userToHandlersMap.get(userId);
        if (set != null) {
            set.remove(handlerId);
            if (set.isEmpty()) {
                userToHandlersMap.remove(userId);
                logger.info("unsubscribe user {} {} ok: cleared.", userId, handlerId);
            } else {
                logger.info("unsubscribe user {} {} ok: but still others online.", userId, handlerId);
            }
        }
    }
}
