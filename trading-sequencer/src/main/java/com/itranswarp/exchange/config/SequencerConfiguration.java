/* SequencerConfiguration：集中装配本应用服务、资源生命周期与健康检查，启动入口不承载业务。 */
package com.itranswarp.exchange.config;

import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.sequencer.SequenceService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class SequencerConfiguration {
  /** 检查存储家族后加载目录。 */
  @Bean
  SecurityCatalog securityCatalog(DataSource source) {
    return SecurityCatalog.load(source);
  }

  /** 仅装配新定序组件。 */
  @Bean(destroyMethod = "close")
  SequenceService sequenceService(
      DataSource source,
      SecurityCatalog catalog,
      @Value("${securities.kafka-bootstrap}") String bootstrap,
      @Value("${securities.namespace:securities.v1}") String namespace) {
    return new SequenceService(source, catalog, bootstrap, namespace);
  }

  /** 完成装配后启动，不使用旧 PostConstruct 路径。 */
  @Bean
  ApplicationRunner startSequencer(SequenceService runtime) {
    return args -> runtime.start();
  }

  /** 就绪跟随实际消费状态，不固定返回 UP。 */
  @Bean
  org.springframework.boot.actuate.health.HealthIndicator securitiesHealth(
      SequenceService runtime) {
    return () ->
        (runtime.ready()
                ? org.springframework.boot.actuate.health.Health.up()
                : org.springframework.boot.actuate.health.Health.down())
            .build();
  }
}
