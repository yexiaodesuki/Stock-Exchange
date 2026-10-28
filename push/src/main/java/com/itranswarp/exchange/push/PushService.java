/* 推送订阅服务：管理 Redis 连接、订阅确认、重试和就绪，不直接维护浏览器会话。 */
package com.itranswarp.exchange.push;

import com.itranswarp.exchange.push.web.PushVerticle;
import com.itranswarp.exchange.securities.output.MarketCache;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.*;
import java.util.concurrent.*;

public final class PushService implements AutoCloseable {
  private final MarketCache cache;
  private final PushVerticle sessions;
  private volatile boolean ready;
  private StatefulRedisPubSubConnection<String, String> subscription;
  private ScheduledExecutorService worker;
  private boolean listenerInstalled;

  /** Redis 消息只交给已固定身份的会话分发器。 */
  public PushService(MarketCache cache, PushVerticle sessions) {
    this.cache = cache;
    this.sessions = sessions;
  }

  /** 保持专属订阅线程，禁止阻塞 Vert.x 事件循环。 */
  public void start() {
    worker =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              var t = new Thread(task, "securities-push-subscribe");
              t.setDaemon(true);
              return t;
            });
    worker.scheduleWithFixedDelay(this::subscribe, 0, 2, TimeUnit.SECONDS);
  }

  /** 必须收到实际订阅确认后才就绪；掉线清除就绪并触发重拉。 */
  private synchronized void subscribe() {
    try {
      if (subscription == null) {
        if (!listenerInstalled) {
          cache
              .client()
              .addListener(
                  new RedisConnectionStateListener() {
                    /** 断开后降级健康，并提醒现有连接 REST 重拉，补偿 Pub/Sub 无离线历史。 */
                    @Override
                    public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
                      ready = false;
                      sessions.resync();
                    }
                  });
          listenerInstalled = true;
        }
        subscription = cache.client().connectPubSub();
        subscription.addListener(
            new RedisPubSubAdapter<>() {
              /** 消息按服务器 Redis 路由分发，客户端不能选择另一用户频道。 */
              @Override
              public void message(String pattern, String channel, String message) {
                sessions.dispatch(channel, message);
              }
            });
      }
      subscription.sync().psubscribe(cache.prefix() + ":market:*", cache.prefix() + ":user:*");
      if (!ready) {
        ready = true;
        sessions.resync();
      }
    } catch (Exception unavailable) {
      ready = false;
    }
  }

  /** 供 HTTP 健康接口与握手检查读取，不把连接存在误判为订阅成功。 */
  public boolean ready() {
    return ready;
  }

  /** 与订阅共用锁，先等本轮订阅结束再停止重试，保持原关闭与重连互斥边界。 */
  public synchronized void stop() {
    ready = false;
    if (worker != null) worker.shutdownNow();
  }

  /** 最后释放订阅连接，不删除任何 Redis 数据。 */
  @Override
  public synchronized void close() {
    stop();
    if (subscription != null) subscription.close();
  }
}
