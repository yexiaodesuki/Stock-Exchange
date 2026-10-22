/* Vert.x 推送生命周期：观察实际 Redis 订阅与断连，保留原连接配置和失败退出策略。 */
package com.itranswarp.exchange.push;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.itranswarp.exchange.redis.RedisCache;
import com.itranswarp.exchange.support.LoggerSupport;

import io.vertx.core.Vertx;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.ResponseType;
import io.vertx.redis.client.impl.types.BulkType;

@Component
public class PushService extends LoggerSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private PushMonitoringService monitoring;
    private Redis redis;

    @Value("${server.port}")
    private int serverPort;

    @Value("${exchange.config.hmac-key}")
    String hmacKey;

    @Value("${spring.redis.standalone.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.standalone.port:6379}")
    private int redisPort;

    @Value("${spring.redis.standalone.password:}")
    private String redisPassword;

    @Value("${spring.redis.standalone.database:0}")
    private int redisDatabase = 0;

    private Vertx vertx;

    /** 建立业务监听及真实订阅；异步回调更新状态，不以管理接口存活代替订阅成功。 */
    @PostConstruct
    public void startVertx() {
        logger.info("start vertx...");
        this.vertx = Vertx.vertx();

        var push = new PushVerticle(this.hmacKey, this.serverPort, monitoring);
        vertx.deployVerticle(push);

        String url = "redis://" + (this.redisPassword.isEmpty() ? "" : ":" + this.redisPassword + "@") + this.redisHost
                + ":" + this.redisPort + "/" + this.redisDatabase;

        logger.info("create redis client for push subscription");
        redis = Redis.createClient(vertx, url);

        // 观察同一订阅连接的结束和异常，断开后立即撤销订阅状态，不自动重连。
        redis.connect().onSuccess(conn -> {
            monitoring.connected(true);
            conn.endHandler(ignored -> monitoring.connected(false));
            conn.exceptionHandler(error -> { monitoring.connected(false); monitoring.count("redis", "failure"); });
            logger.info("connect to redis ok.");
            conn.handler(response -> {
                if (response.type() == ResponseType.PUSH) {
                    int size = response.size();
                    if (size == 3) {
                        Response type = response.get(2);
                        if (type instanceof BulkType) {
                            String msg = type.toString();
                            monitoring.count("notification", "received");
                            if (logger.isDebugEnabled()) {
                                logger.debug("receive push message: {}", msg);
                            }
                            push.broadcast(msg);
                        }
                    }
                }
            });
            logger.info("try subscribe...");
            conn.send(Request.cmd(Command.SUBSCRIBE).arg(RedisCache.Topic.NOTIFICATION)).onSuccess(resp -> {
                monitoring.subscribed(true);
                logger.info("subscribe ok.");
            }).onFailure(err -> {
                monitoring.subscribed(false);
                logger.error("subscribe failed.", err);
                System.exit(1);
            });
        }).onFailure(err -> {
            monitoring.connected(false);
            logger.error("connect to redis failed.", err);
            System.exit(1);
        });
    }

    /** 正常关闭时撤销状态，释放客户端和事件循环，不清理任何通知或缓存。 */
    @jakarta.annotation.PreDestroy
    public void stop() {
        monitoring.stopping();
        if (redis != null) redis.close();
        if (vertx != null) vertx.close();
    }

    /** 原有退出入口保留退出码，退出前撤销状态。 */
    void exit(int exitCode) {
        monitoring.stopping();
        this.vertx.close();
        System.exit(exitCode);
    }
}
