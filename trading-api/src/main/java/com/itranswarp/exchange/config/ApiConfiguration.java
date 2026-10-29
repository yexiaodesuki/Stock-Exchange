/* ApiConfiguration：集中装配本应用服务、资源生命周期与健康检查，启动入口不承载业务。 */
package com.itranswarp.exchange.config;

import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.*;
import com.itranswarp.exchange.service.*;
import com.itranswarp.exchange.web.api.TradingApiController;
import com.itranswarp.exchange.web.json.PlainDecimalSerializer;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class ApiConfiguration {
  /** 默认关闭模拟开关，本地配置显式开启，额度不可由浏览器覆盖。 */
  @Bean
  DepositPolicy depositPolicy(
      @Value("${securities.deposits.enabled:false}") boolean enabled,
      @Value("${securities.deposits.minimum:1.00}") java.math.BigDecimal minimum,
      @Value("${securities.deposits.maximum:100000.00}") java.math.BigDecimal maximum,
      @Value("${securities.deposits.daily:500000.00}") java.math.BigDecimal daily) {
    return new DepositPolicy(enabled,minimum,maximum,daily);
  }

  /** 显式依赖已迁移目录，后台不得抢在 Flyway 建表前读取发件箱。 */
  @Bean
  com.itranswarp.exchange.repository.DepositRepository deposits(DataSource source,SecurityCatalog catalog) {
    return new com.itranswarp.exchange.repository.DepositRepository(source);
  }

  /** 使用 UTC 日额度，不随宿主机时区改变。 */
  @Bean
  DepositService depositService(com.itranswarp.exchange.repository.DepositRepository deposits,DepositPolicy policy) {
    return new DepositService(deposits,policy,java.time.Clock.systemUTC());
  }

  /** 生命周期由 Spring 管理，不增加容器、不绕过定序。 */
  @Bean(initMethod="start",destroyMethod="close")
  DepositDispatchService depositDispatcher(com.itranswarp.exchange.repository.DepositRepository deposits,
      DataSource source,TradingEngineApiProxyService engineProxy,
      @Value("${securities.kafka-bootstrap}") String bootstrap,
      @Value("${securities.namespace}") String namespace) {
    return new DepositDispatchService(deposits,new OutputStore(source),engineProxy,bootstrap,namespace);
  }

  /** 入金与订单控制器分开，复用现有签名身份服务。 */
  @Bean
  com.itranswarp.exchange.web.api.DepositController depositController(ApiAuthenticationService authentication,DepositService service) {
    return new com.itranswarp.exchange.web.api.DepositController(authentication,service);
  }

  /** 目录严格检查证券数据库。 */
  @Bean
  SecurityCatalog catalog(DataSource source) {
    return SecurityCatalog.load(source);
  }

  /** 缓存延迟连接，市场查询可以回源。 */
  @Bean(destroyMethod = "close")
  MarketCache cache(
      @Value("${securities.redis-uri}") String uri,
      @Value("${securities.namespace}") String namespace) {
    return new MarketCache(uri, namespace);
  }

  /** 不使用任何默认认证密钥。 */
  @Bean
  SecuritiesTokens tokens(@Value("${securities.auth-key}") String key) {
    return new SecuritiesTokens(key);
  }

  /** 引擎代理只负责内部 HTTP 与失败映射。 */
  @Bean
  TradingEngineApiProxyService engineProxy(@Value("${securities.engine-url}") String engine) {
    return new TradingEngineApiProxyService(engine);
  }

  /** 认证和票据服务不访问订单持久化。 */
  @Bean
  ApiAuthenticationService authentication(SecuritiesTokens tokens, MarketCache cache) {
    return new ApiAuthenticationService(tokens, cache);
  }

  /** 历史查询独立于当前订单和行情缓存。 */
  @Bean
  HistoryService history(
      DataSource source, SecurityCatalog catalog, TradingEngineApiProxyService engineProxy) {
    return new HistoryService(source, catalog, engineProxy);
  }

  /** 行情查询统一执行缓存版本校验及回源。 */
  @Bean
  MarketQueryService marketQueries(
      DataSource source,
      SecurityCatalog catalog,
      MarketCache cache,
      TradingEngineApiProxyService engineProxy) {
    return new MarketQueryService(source, catalog, cache, engineProxy);
  }

  /** 生产者只由发送服务持有和关闭，避免重复释放资源。 */
  @Bean(destroyMethod = "close")
  SendEventService sender(
      DataSource source,
      TradingEngineApiProxyService engineProxy,
      @Value("${securities.kafka-bootstrap}") String bootstrap,
      @Value("${securities.namespace}") String namespace) {
    return new SendEventService(source, bootstrap, namespace, engineProxy);
  }

  /** 控制器只注入业务服务；生产者销毁交给 sender Bean，关闭顺序由依赖关系管理。 */
  @Bean(destroyMethod = "")
  TradingApiController controller(
      SecurityCatalog catalog,
      ApiAuthenticationService authentication,
      TradingEngineApiProxyService engineProxy,
      HistoryService history,
      MarketQueryService marketQueries,
      SendEventService sender) {
    return new TradingApiController(
        catalog, authentication, engineProxy, history, marketQueries, sender);
  }

  /** 公共 API 的 Long 与 BigDecimal 以十进制字符串输出，避免 JavaScript 精度损失。 */
  @Bean
  org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
      exactNumbers() {
    return builder -> {
      builder.serializerByType(
          Long.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
      builder.serializerByType(
          Long.TYPE, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
      builder.serializerByType(java.math.BigDecimal.class, new PlainDecimalSerializer());
    };
  }

  /** 核心不可用时 API 明确降级，不能因为 HTTP 端口存活便报告正常。 */
  @Bean
  org.springframework.boot.actuate.health.HealthIndicator securitiesHealth(
      TradingApiController controller) {
    return () ->
        (controller.ready()
                ? org.springframework.boot.actuate.health.Health.up()
                : org.springframework.boot.actuate.health.Health.down())
            .build();
  }
}
