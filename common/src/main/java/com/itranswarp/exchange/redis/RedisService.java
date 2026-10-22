/* Redis 调用与连接池监控，不改变业务命令或超时。 */
package com.itranswarp.exchange.redis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;

import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.support.ConnectionPoolSupport;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.itranswarp.exchange.util.ClassPathUtil;

@Component
public class RedisService {

    @Autowired
    private com.itranswarp.exchange.monitoring.PipelineMonitoring monitoring = new com.itranswarp.exchange.monitoring.PipelineMonitoring();

    /** 注入注册表后绑定真实业务连接池，只读取计数。 */
    @jakarta.annotation.PostConstruct
    public void bindMonitoring() {
        monitoring.gauge("exchange.redis.pool.active", m -> redisConnectionPool.getNumActive());
        monitoring.gauge("exchange.redis.pool.idle", m -> redisConnectionPool.getNumIdle());
        monitoring.gauge("exchange.redis.pool.waiting", m -> redisConnectionPool.getNumWaiters());
    }

    final Logger logger = LoggerFactory.getLogger(getClass());

    final RedisClient redisClient;

    final GenericObjectPool<StatefulRedisConnection<String, String>> redisConnectionPool;

    public RedisService(@Autowired RedisConfiguration redisConfig) {
        RedisURI uri = RedisURI.Builder.redis(redisConfig.getHost(), redisConfig.getPort())
                .withPassword(redisConfig.getPassword().toCharArray()).withDatabase(redisConfig.getDatabase()).build();
        this.redisClient = RedisClient.create(uri);

        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(5);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        this.redisConnectionPool = ConnectionPoolSupport.createGenericObjectPool(() -> redisClient.connect(),
                poolConfig);
    }

    @PreDestroy
    public void shutdown() {
        this.redisConnectionPool.close();
        this.redisClient.shutdown();
    }

    /**
     * Load Lua script from classpath file and return SHA as string.
     *
     * @param classpathFile Script path.
     * @return SHA as string.
     */
    public String loadScriptFromClassPath(String classpathFile) {
        String sha = executeSync(commands -> {
            try {
                return commands.scriptLoad(ClassPathUtil.readFile(classpathFile));
            } catch (IOException e) {
                throw new UncheckedIOException("load file from classpath failed: " + classpathFile, e);
            }
        });
        if (logger.isInfoEnabled()) {
            logger.info("loaded script {} from {}.", sha, classpathFile);
        }
        return sha;
    }

    /**
     * Load Lua script and return SHA as string.
     *
     * @param scriptContent Script content.
     * @return SHA as string.
     */
    String loadScript(String scriptContent) {
        return executeSync(commands -> {
            return commands.scriptLoad(scriptContent);
        });
    }

    /** 建立实际结果订阅后观察连接开放状态，不以独立 PING 代表订阅正常。 */
    public void subscribe(String channel, Consumer<String> listener) {
        StatefulRedisPubSubConnection<String, String> conn = this.redisClient.connectPubSub();
        java.util.concurrent.atomic.AtomicBoolean subscribed = new java.util.concurrent.atomic.AtomicBoolean();
        // 区分客户端对象未关闭和真实连接断开；自动重订阅确认后才重新就绪。
        this.redisClient.addListener(new io.lettuce.core.RedisConnectionStateListener() {
            /** 只观察这个订阅连接，不把业务池其他连接关闭误认为订阅失败。 */
            @Override public void onRedisDisconnected(io.lettuce.core.RedisChannelHandler<?, ?> connection) {
                if (connection == conn) subscribed.set(false);
            }
        });
        conn.addListener(new RedisPubSubAdapter<String, String>() {
            /** 真实订阅确认也涵盖自动重连后的重订阅。 */
            @Override public void subscribed(String subscribedChannel, long count) {
                if (channel.equals(subscribedChannel)) subscribed.set(true);
            }
            /** 明确取消订阅时立即撤销状态。 */
            @Override public void unsubscribed(String subscribedChannel, long count) {
                if (channel.equals(subscribedChannel)) subscribed.set(false);
            }
            @Override
            public void message(String channel, String message) {
                listener.accept(message);
            }
        });
        conn.sync().subscribe(channel);
        monitoring.component("api_result_subscription", () -> conn.isOpen() && subscribed.get());
    }

    public Boolean executeScriptReturnBoolean(String sha, String[] keys, String[] values) {
        return executeSync(commands -> {
            return commands.evalsha(sha, ScriptOutputType.BOOLEAN, keys, values);
        });
    }

    public String executeScriptReturnString(String sha, String[] keys, String[] values) {
        return executeSync(commands -> {
            return commands.evalsha(sha, ScriptOutputType.VALUE, keys, values);
        });
    }

    public String get(String key) {
        return executeSync((commands) -> {
            return commands.get(key);
        });
    }

    public void publish(String topic, String data) {
        executeSync((commands) -> {
            return commands.publish(topic, data);
        });
    }

    public List<String> lrange(String key, long start, long end) {
        return executeSync((commands) -> {
            return commands.lrange(key, start, end);
        });
    }

    public List<String> zrangebyscore(String key, long start, long end) {
        return executeSync((commands) -> {
            return commands.zrangebyscore(key, Range.create(start, end));
        });
    }

    /** 记录借用连接及命令耗时，保留原异常包装与归还行为。 */
    public <T> T executeSync(SyncCommandCallback<T> callback) {
        long started = System.nanoTime();
        try (StatefulRedisConnection<String, String> connection = redisConnectionPool.borrowObject()) {
            connection.setAutoFlushCommands(true);
            RedisCommands<String, String> commands = connection.sync();
            T result = callback.doInConnection(commands);
            monitoring.count("redis_command", "success");
            return result;
        } catch (Exception e) {
            monitoring.count("redis_command", "failure");
            logger.warn("executeSync redis failed.", e);
            throw new RuntimeException(e);
        } finally {
            monitoring.duration("redis_command", started);
        }
    }
}
