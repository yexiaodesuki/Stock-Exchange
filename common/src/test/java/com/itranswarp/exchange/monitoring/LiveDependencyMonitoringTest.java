/* 显式开启的只读容器联调：复用生产探测器，只校验连接，不写表、缓存或 Kafka Topic。 */
package com.itranswarp.exchange.monitoring;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.kafka.core.KafkaAdmin;
import com.itranswarp.exchange.redis.RedisConfiguration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "EXCHANGE_MONITORING_LIVE_TESTS", matches = "true")
class LiveDependencyMonitoringTest {
    /** 真实 PING、JDBC isValid、Kafka describeCluster 都必须成功，且缓存健康查询不增加探测次数。 */
    @Test void readOnlyProbesAgainstRunningContainers() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv().getOrDefault("LIVE_JDBC_URL", "jdbc:mysql://localhost:3306/exchange?useSSL=false&allowPublicKeyRetrieval=true"));
        config.setUsername(System.getenv().getOrDefault("LIVE_DB_USERNAME", "root"));
        config.setPassword(System.getenv("LIVE_DB_PASSWORD"));
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setConnectionTimeout(2000); config.setReadOnly(true);
        var dataSource = new HikariDataSource(config);
        var redis = new RedisConfiguration(); redis.setHost("localhost"); redis.setPort(6379); redis.setDatabase(0); redis.setPassword("");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("dataSource", dataSource); beans.registerSingleton("redisConfiguration", redis);
        beans.registerSingleton("kafkaAdmin", new KafkaAdmin(Map.of("bootstrap.servers", "localhost:9092")));
        var registry = new SimpleMeterRegistry();
        var probes = new DependencyMonitoringService(registry, beans.getBeanProvider(javax.sql.DataSource.class),
                beans.getBeanProvider(RedisConfiguration.class), beans.getBeanProvider(KafkaAdmin.class), "mysql,redis,kafka", 15000);
        try {
            probes.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!probes.health().getStatus().equals(org.springframework.boot.actuate.health.Status.UP) && System.nanoTime() < deadline) Thread.sleep(50);
            assertEquals(org.springframework.boot.actuate.health.Status.UP, probes.health().getStatus());
            for (String dependency : new String[] { "mysql", "redis", "kafka" }) {
                assertEquals(1, registry.get("exchange.dependency.up").tag("dependency", dependency).gauge().value());
                double count = registry.get("exchange.dependency.checks").tags("dependency", dependency, "result", "success").counter().count();
                probes.health(); probes.health();
                assertEquals(count, registry.get("exchange.dependency.checks").tags("dependency", dependency, "result", "success").counter().count());
            }
        } finally { probes.stop(); dataSource.close(); registry.close(); }
    }
}
