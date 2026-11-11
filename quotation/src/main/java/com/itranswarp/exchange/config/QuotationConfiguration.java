/* QuotationConfiguration：集中装配本应用服务、资源生命周期与健康检查，启动入口不承载业务。 */
package com.itranswarp.exchange.config;

import com.itranswarp.exchange.quotation.QuotationService;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.MarketCache;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class QuotationConfiguration {
  /** 验证目录来源，不能读取旧 BTC 数据库。 */
  @Bean
  SecurityCatalog catalog(DataSource source) {
    return SecurityCatalog.load(source);
  }

  /** 证券/测试前缀由同一配置传递。 */
  @Bean(destroyMethod = "close")
  MarketCache cache(
      @Value("${securities.redis-uri}") String uri,
      @Value("${securities.namespace}") String namespace) {
    return new MarketCache(uri, namespace);
  }

  /** 运行时拥有单独消费进度，不依赖旧 IP 消费组。 */
  @Bean(destroyMethod = "close")
  QuotationService quotationService(DataSource source, SecurityCatalog catalog, MarketCache cache) {
    return new QuotationService(source, catalog, cache);
  }

  /** 启动恢复投影，错误保持 DOWN 并继续尝试，不假装空市场。 */
  @Bean
  ApplicationRunner start(
      QuotationService runtime, com.itranswarp.exchange.securities.output.TopicSignal signal) {
    return args -> {
      runtime.start();
      signal.start();
    };
  }

  /** 行情低延迟提示与持久进度分开，掉消息时数据库轮询兜底。 */
  @Bean(destroyMethod = "close")
  com.itranswarp.exchange.securities.output.TopicSignal signal(
      QuotationService runtime,
      @Value("${securities.kafka-bootstrap}") String bootstrap,
      @Value("${securities.namespace}") String namespace) {
    return new com.itranswarp.exchange.securities.output.TopicSignal(
        bootstrap, namespace, runtime::poll, runtime::ready);
  }

  /** 管理端口报告实际投影和缓存状态。 */
  @Bean
  org.springframework.boot.actuate.health.HealthIndicator securitiesHealth(
      QuotationService runtime) {
    return () ->
        (runtime.ready()
                ? org.springframework.boot.actuate.health.Health.up()
                : org.springframework.boot.actuate.health.Health.down())
            .build();
  }
}
