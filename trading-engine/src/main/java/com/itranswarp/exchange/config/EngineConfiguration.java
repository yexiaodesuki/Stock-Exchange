/* EngineConfiguration：集中装配本应用服务、资源生命周期与健康检查，启动入口不承载业务。 */
package com.itranswarp.exchange.config;

import com.itranswarp.exchange.TradingEngineService;
import com.itranswarp.exchange.messaging.KafkaHintConsumer;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.web.api.InternalTradingEngineApiController;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@Import(InternalTradingEngineApiController.class)
public class EngineConfiguration {
  /** 从版本检查通过的数据源读取目录；没有目录时启动失败。 */
  @Bean
  SecurityCatalog securityCatalog(DataSource source) {
    return SecurityCatalog.load(source);
  }

  /** 显式装配新运行时，关闭时只释放本实例资源。 */
  @Bean(destroyMethod = "close")
  TradingEngineService tradingEngineService(
      DataSource source,
      SecurityCatalog catalog,
      @Value("${securities.snapshot-interval-ms:60000}") long interval) {
    return new TradingEngineService(source, catalog, interval);
  }

  /** ApplicationRunner 完成恢复后 Boot 才完成启动。 */
  @Bean
  ApplicationRunner recover(
      TradingEngineService runtime,
      KafkaHintConsumer hints,
      org.springframework.beans.factory.ObjectProvider<
              com.itranswarp.exchange.securities.output.OutboxPublisher>
          sender) {
    return args -> {
      runtime.start();
      hints.start();
      sender.ifAvailable(com.itranswarp.exchange.securities.output.OutboxPublisher::start);
    };
  }

  /** 缓存连接延迟建立，Redis 故障不改变现金恢复结果。 */
  @Bean(destroyMethod = "close")
  com.itranswarp.exchange.securities.output.MarketCache marketCache(
      @Value("${securities.redis-uri:redis://127.0.0.1:16379/0}") String uri,
      @Value("${securities.namespace:securities.v1}") String namespace) {
    return new com.itranswarp.exchange.securities.output.MarketCache(uri, namespace);
  }

  /** 只在 READY 后发送；测试可关闭外部发送而仍验证持久输出事务。 */
  @Bean(destroyMethod = "close")
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "securities.send-enabled",
      havingValue = "true",
      matchIfMissing = true)
  com.itranswarp.exchange.securities.output.OutboxPublisher outbox(
      DataSource source,
      TradingEngineService runtime,
      SecurityCatalog catalog,
      com.itranswarp.exchange.securities.output.MarketCache cache,
      @Value("${securities.kafka-bootstrap}") String bootstrap,
      @Value("${securities.namespace:securities.v1}") String namespace) {
    var sender =
        new com.itranswarp.exchange.securities.output.OutboxPublisher(
            source,
            cache,
            bootstrap,
            namespace,
            () -> runtime.status() == TradingEngineService.Status.READY ? runtime.applied() : -1);
    // 完整重建时静默写缓存，客户端重连通过 HTTP 拉取当前版本。
    sender.refreshBooks(
        () ->
            catalog
                .all()
                .forEach(
                    s -> {
                      var book = runtime.book(s.securityId());
                      cache.put(s.securityId(), "book", book.sequenceId(), book);
                    }));
    return sender;
  }

  /** Kafka 是低延迟提示链路，数据库追赶独立运行。 */
  @Bean(destroyMethod = "close")
  KafkaHintConsumer kafkaHints(
      TradingEngineService runtime,
      @Value("${securities.kafka-bootstrap}") String bootstrap,
      @Value("${securities.namespace:securities.v1}") String namespace) {
    return new KafkaHintConsumer(runtime, bootstrap, namespace);
  }

  /** Actuator 就绪与业务状态一致，不将恢复中或失败引擎报告为健康。 */
  @Bean
  org.springframework.boot.actuate.health.HealthIndicator securitiesHealth(
      TradingEngineService runtime) {
    return () ->
        (runtime.status() == TradingEngineService.Status.READY
                ? org.springframework.boot.actuate.health.Health.up()
                : org.springframework.boot.actuate.health.Health.down())
            .withDetail("state", runtime.status())
            .build();
  }

  /** 固定低基数指标呈现已执行位置与就绪，不引入用户维度或改动旧仪表盘。 */
  @Bean
  io.micrometer.core.instrument.binder.MeterBinder securitiesMetrics(TradingEngineService runtime) {
    return registry -> {
      io.micrometer.core.instrument.Gauge.builder(
              "exchange.securities.applied", runtime, TradingEngineService::applied)
          .register(registry);
      io.micrometer.core.instrument.Gauge.builder(
              "exchange.securities.target", runtime, TradingEngineService::target)
          .register(registry);
      io.micrometer.core.instrument.Gauge.builder(
              "exchange.securities.ready",
              runtime,
              r -> r.status() == TradingEngineService.Status.READY ? 1 : 0)
          .register(registry);
    };
  }
}
