/* WebSocket 会话与分发：仅负责连接、证券订阅、私人路由、背压和心跳，不消费 Redis。 */
package com.itranswarp.exchange.push.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.MarketCache;
import com.itranswarp.exchange.securities.output.WireJson;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PushVerticle implements AutoCloseable {
  private final Vertx vertx;
  private final MarketCache cache;
  private final SecurityCatalog catalog;
  private final Map<ServerWebSocket, Session> sockets = new ConcurrentHashMap<>();
  private final ObjectMapper json = new ObjectMapper();
  private long timer;

  /** 会话只属于当前进程；身份在握手时固定，不能由订阅消息改写。 */
  public PushVerticle(Vertx vertx, MarketCache cache, SecurityCatalog catalog) {
    this.vertx = vertx;
    this.cache = cache;
    this.catalog = catalog;
  }

  /** 保持原心跳周期、过期和空闲关闭规则。 */
  public void startHeartbeat() {
    timer =
        vertx.setPeriodic(
            20000,
            ignored -> {
              long now = System.currentTimeMillis();
              sockets.forEach(
                  (socket, session) -> {
                    if (now >= session.expires || now - session.lastSeen > 60000)
                      socket.close((short) 1008, "session expired");
                    else socket.writePing(Buffer.buffer("heartbeat"));
                  });
            });
  }

  /** 创建独立会话，安装订阅、心跳和关闭回调。 */
  public void connected(ServerWebSocket socket, MarketCache.Ticket identity) {
    var session = new Session(identity.userId(), identity.expiresAt());
    sockets.put(socket, session);
    socket.setWriteQueueMaxSize(262144);
    socket.closeHandler(ignored -> sockets.remove(socket));
    socket.exceptionHandler(
        error -> {
          sockets.remove(socket);
          socket.close();
        });
    socket.pongHandler(ignored -> session.lastSeen = System.currentTimeMillis());
    socket.textMessageHandler(
        text -> {
          session.lastSeen = System.currentTimeMillis();
          try {
            if (session.lastSeen - session.windowStart >= 1000) {
              session.windowStart = session.lastSeen;
              session.messages = 0;
            }
            if (++session.messages > 20) {
              socket.close((short) 1008, "subscription rate exceeded");
              return;
            }
            if (text.length() > 4096) throw new IllegalArgumentException();
            var message = json.readTree(text);
            String action = message.path("action").asText();
            if (!Set.of("subscribe", "unsubscribe").contains(action)
                || !message.path("securityIds").isArray()
                || message.path("securityIds").size() > 5) throw new IllegalArgumentException();
            var requested = new HashSet<Long>();
            for (var id : message.path("securityIds")) {
              long security = id.asLong(-1);
              catalog.require(security);
              requested.add(security);
            }
            if (action.equals("subscribe")) session.securities.addAll(requested);
            else session.securities.removeAll(requested);
            write(
                socket,
                "{\"type\":\"subscription.ack\",\"securityIds\":" + session.securities + "}");
          } catch (Exception invalid) {
            write(socket, "{\"type\":\"error\",\"code\":\"INVALID_SUBSCRIPTION\"}");
          }
        });
    write(socket, "{\"type\":\"welcome\",\"resyncRequired\":true}");
  }

  /** 公共消息按证券过滤，私人消息按握手用户过滤。 */
  public void dispatch(String channel, String message) {
    String market = cache.prefix() + ":market:", user = cache.prefix() + ":user:";
    try {
      if (channel.startsWith(market)) {
        long security = Long.parseLong(channel.substring(market.length()));
        catalog.require(security);
        sockets.forEach(
            (socket, session) -> {
              if (session.securities.contains(security)
                  && session.expires > System.currentTimeMillis()) write(socket, message);
            });
      } else if (channel.startsWith(user)) {
        long target = Long.parseLong(channel.substring(user.length()));
        sockets.forEach(
            (socket, session) -> {
              if (session.user == target && session.expires > System.currentTimeMillis())
                write(socket, message);
            });
      }
    } catch (RuntimeException invalid) {
      /* 非法服务器路由不能退化为全局广播。 */
    }
  }

  /** 背压与精确数值处理保持不变，慢客户端关闭后自行重连。 */
  private void write(ServerWebSocket socket, String message) {
    vertx.runOnContext(
        ignored -> {
          if (socket.isClosed()) return;
          if (socket.writeQueueFull()) {
            socket.close((short) 1013, "slow consumer");
            return;
          }
          socket.writeTextMessage(WireJson.text(message)).onFailure(error -> socket.close());
        });
  }

  /** Redis 恢复后要求客户端重新拉取状态，不重放丢失提示。 */
  public void resync() {
    sockets.keySet().forEach(socket -> write(socket, "{\"type\":\"resync.required\"}"));
  }

  /** 关闭会话和心跳，不关闭由协调器持有的 Vert.x。 */
  @Override
  public void close() {
    vertx.cancelTimer(timer);
    sockets.keySet().forEach(ServerWebSocket::close);
  }

  private static final class Session {
    final long user, expires;
    volatile long lastSeen = System.currentTimeMillis();
    final Set<Long> securities = ConcurrentHashMap.newKeySet();
    long windowStart = System.currentTimeMillis();
    int messages;

    /** 身份在握手时固定，后续订阅消息不能覆盖。 */
    Session(long user, long expires) {
      this.user = user;
      this.expires = expires;
    }
  }
}
