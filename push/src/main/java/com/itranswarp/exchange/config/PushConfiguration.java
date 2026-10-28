/* PushConfiguration：集中装配本应用服务、资源生命周期与健康检查，启动入口不承载业务。 */
package com.itranswarp.exchange.config;

import com.itranswarp.exchange.push.web.PushServer;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.MarketCache;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class PushConfiguration {
  /** 只读加载证券目录，不从浏览器接受任意订阅标识。 */
  @Bean
  SecurityCatalog catalog(DataSource source) {
    return SecurityCatalog.load(source);
  }

  /** 与 API 共用前缀，但只通过一次性票据共享身份。 */
  @Bean(destroyMethod = "close")
  MarketCache cache(
      @Value("${securities.redis-uri}") String uri,
      @Value("${securities.namespace}") String namespace) {
    return new MarketCache(uri, namespace);
  }

  /** 监听地址由集中配置提供以适配本机和 Docker，Origin 仍必须明确允许。 */
  @Bean(destroyMethod = "close")
  PushServer push(
      MarketCache cache,
      SecurityCatalog catalog,
      @Value("${server.port}") int port,
      @Value("${securities.origins}") String origins,
      @Value("${server.address:127.0.0.1}") String address) {
    return new PushServer(cache, catalog, port, origins, address);
  }

  /** 依赖装配后启动，监听失败向上抛出。 */
  @Bean
  ApplicationRunner start(PushServer push) {
    return args -> push.start();
  }
}
