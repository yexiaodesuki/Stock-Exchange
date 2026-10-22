/* 应用视角的只读依赖探测：独立定时线程、连接超时及缓存，抓取不访问数据库或业务锁。 */
package com.itranswarp.exchange.monitoring;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;
import com.itranswarp.exchange.redis.RedisConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component("dependenciesHealthIndicator")
@ConditionalOnProperty(name = "exchange.monitoring.dependencies-enabled", havingValue = "true")
public class DependencyMonitoringService implements HealthIndicator {
    private final MeterRegistry registry;
    private final ObjectProvider<DataSource> dataSource;
    private final ObjectProvider<RedisConfiguration> redisConfiguration;
    private final ObjectProvider<KafkaAdmin> kafkaAdmin;
    private final String dependencies;
    private final long intervalMs;
    private final Map<String, DependencyStatus> states = new java.util.concurrent.ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private RedisClient redis;
    private AdminClient kafka;

    /** 只保存提供者，避免公共监控强制实例化不存在的依赖。 */
    public DependencyMonitoringService(MeterRegistry registry, ObjectProvider<DataSource> dataSource,
            ObjectProvider<RedisConfiguration> redisConfiguration, ObjectProvider<KafkaAdmin> kafkaAdmin,
            @Value("${exchange.monitoring.dependencies:mysql,redis,kafka}") String dependencies,
            @Value("${exchange.monitoring.probe-interval-ms:15000}") long intervalMs) {
        this.registry = registry; this.dataSource = dataSource; this.redisConfiguration = redisConfiguration;
        this.kafkaAdmin = kafkaAdmin; this.dependencies = dependencies; this.intervalMs = intervalMs;
    }

    /** 每个依赖独占一个限量守护线程；卡住不累积任务，缓存到期自动不可用。 */
    @PostConstruct public void start() {
        if (intervalMs < 1000) throw new IllegalArgumentException("依赖检查间隔至少一秒");
        scheduler = Executors.newScheduledThreadPool(3, action -> {
            Thread thread = new Thread(action, "dependency-probe"); thread.setDaemon(true); return thread;
        });
        for (String name : dependencies.split(",")) {
            String dependency = name.trim();
            Runnable probe;
            switch (dependency) {
                case "mysql" -> probe = this::mysql;
                case "redis" -> {
                    RedisConfiguration config = redisConfiguration.getObject();
                    RedisURI uri = RedisURI.Builder.redis(config.getHost(), config.getPort())
                            .withPassword(config.getPassword().toCharArray()).withDatabase(config.getDatabase())
                            .withTimeout(Duration.ofSeconds(2)).build();
                    redis = RedisClient.create(uri);
                    redis.setOptions(io.lettuce.core.ClientOptions.builder().socketOptions(
                            io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build()).build());
                    probe = this::redis;
                }
                case "kafka" -> {
                    Map<String, Object> config = new HashMap<>(kafkaAdmin.getObject().getConfigurationProperties());
                    config.put("request.timeout.ms", 2000); config.put("default.api.timeout.ms", 2000);
                    kafka = AdminClient.create(config); probe = this::kafka;
                }
                default -> throw new IllegalArgumentException("未知依赖类型：" + dependency);
            }
            DependencyStatus state = new DependencyStatus(intervalMs * 3);
            states.put(dependency, state);
            Gauge.builder("exchange.dependency.up", state, s -> s.up() ? 1 : 0).tag("dependency", dependency).register(registry);
            Gauge.builder("exchange.dependency.fresh", state, s -> s.fresh() ? 1 : 0).tag("dependency", dependency).register(registry);
            Gauge.builder("exchange.dependency.checked.timestamp", state, DependencyStatus::checkedAt).tag("dependency", dependency).register(registry);
            // 回调只做一次只读探测，失败计数使用固定标签，不泄露异常或连接信息。
            scheduler.scheduleWithFixedDelay(() -> check(dependency, state, probe), 0, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 单次探测失败保留线程，计时和缓存更新不进入业务事务。 */
    void check(String name, DependencyStatus state, Runnable probe) {
        long start = System.nanoTime(); boolean success = false;
        try { probe.run(); success = true; }
        catch (RuntimeException ignored) { /* 具体业务异常仍由业务日志定位。 */ }
        finally {
            state.record(success);
            registry.counter("exchange.dependency.checks", "dependency", name, "result", success ? "success" : "failure").increment();
            registry.timer("exchange.dependency.duration", "dependency", name).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** 借用真实应用连接池，受现有获取连接超时及驱动校验超时约束，不写数据库。 */
    private void mysql() {
        try (var connection = dataSource.getObject().getConnection()) {
            if (!connection.isValid(2)) throw new IllegalStateException("数据库校验失败");
        } catch (Exception e) { throw new IllegalStateException("数据库探测失败", e); }
    }

    /** 使用独立限时连接，避免修改业务 Redis 连接和命令超时。 */
    private void redis() {
        try (var connection = redis.connect()) {
            if (!"PONG".equals(connection.sync().ping())) throw new IllegalStateException("Redis 探测失败");
        }
    }

    /** 仅读取集群元数据，不加入消费组、不创建 Topic、不重置 offset。 */
    private void kafka() {
        try { kafka.describeCluster().clusterId().get(2, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException("Kafka 探测失败", e); }
    }

    /** 健康查询只读取缓存，不把外部故障加入 liveness/readiness 分组。 */
    @Override public Health health() {
        return !states.isEmpty() && states.values().stream().allMatch(DependencyStatus::up)
                ? Health.up().build() : Health.down().build();
    }

    /** 停止探测并关闭独立客户端，不关闭业务池。 */
    @PreDestroy public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (kafka != null) kafka.close(Duration.ofSeconds(2));
        if (redis != null) redis.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
}
