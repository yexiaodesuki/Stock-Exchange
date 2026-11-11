/* UIConfiguration：集中装配本应用服务、资源生命周期与健康检查，启动入口不承载业务。 */
package com.itranswarp.exchange.config;

import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.SecuritiesTokens;
import com.itranswarp.exchange.ui.demo.DemoBootstrap;
import com.itranswarp.exchange.ui.service.UserService;
import com.itranswarp.exchange.ui.web.MvcController;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class UIConfiguration {
  /** 启动时检查数据库家族与目录，不能误连旧 exchange。 */
  @Bean
  SecurityCatalog catalog(DataSource source) {
    return SecurityCatalog.load(source);
  }

  /** 只装配证券身份存储。 */
  @Bean
  UserService users(DataSource source) {
    return new UserService(source);
  }

  /** 认证签名只留在服务端。 */
  @Bean
  SecuritiesTokens tokens(@Value("${securities.auth-key}") String key) {
    return new SecuritiesTokens(key);
  }

  /** 演示开关默认关闭，缺失演示密码时不能用弱默认值初始化。 */
  @Bean(destroyMethod = "close")
  DemoBootstrap demo(
      DataSource source,
      UserService users,
      SecurityCatalog catalog,
      @Value("${securities.kafka-bootstrap}") String bootstrap,
      @Value("${securities.namespace}") String namespace,
      @Value("${securities.engine-url}") String engine,
      @Value("${securities.demo.enabled:false}") boolean enabled,
      org.springframework.core.env.Environment environment) {
    var passwords =
        java.util.stream.IntStream.range(0, 10)
            .mapToObj(i -> environment.getProperty("securities.demo.passwords[" + i + "]", ""))
            .toList();
    return new DemoBootstrap(source, users, bootstrap, namespace, engine, enabled, passwords);
  }

  /** 启用初始化时必须完成或明确保留旧环境，不能只因 HTTP 端口开启而报告健康。 */
  @Bean
  org.springframework.boot.actuate.health.HealthIndicator demoReadiness(DemoBootstrap demo) {
    return () -> {
      var status = demo.status();
      boolean ready =
          !(Boolean) status.get("enabled")
              || java.util.Set.of("COMPLETE", "LEGACY").contains(status.get("state"));
      return (ready
              ? org.springframework.boot.actuate.health.Health.up()
              : org.springframework.boot.actuate.health.Health.down())
          .withDetail("initialization", status.get("state"))
          .build();
    };
  }

  /** 页面可显示初始化进度；后台命令通过已有定序与引擎执行。 */
  @Bean
  ApplicationRunner initializeDemo(DemoBootstrap demo) {
    return args -> demo.start();
  }

  /** 显式注册控制器，不启用旧代理、登录拦截器或自动测试用户。 */
  @Bean
  MvcController web(
      UserService users,
      SecuritiesTokens tokens,
      DemoBootstrap demo,
      @Value("${securities.api-url}") String api,
      @Value("${securities.websocket-url}") String websocket) {
    return new MvcController(users, tokens, demo, api, websocket);
  }

  /** 最高优先级安全过滤器保护登录与所有交易写请求。 */
  @Bean
  FilterRegistrationBean<MvcController> securityFilter(MvcController web) {
    var bean = new FilterRegistrationBean<>(web);
    bean.setOrder(-100);
    bean.addUrlPatterns("/*");
    return bean;
  }

  /** Tomcat 版本默认不一定识别 mjs；严格 MIME 浏览器必须收到 JavaScript 而非二进制类型。 */
  @Bean
  org.springframework.boot.web.server.WebServerFactoryCustomizer<
          org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory>
      moduleMimeTypes() {
    return factory -> {
      var mappings =
          new org.springframework.boot.web.server.MimeMappings(
              org.springframework.boot.web.server.MimeMappings.DEFAULT);
      mappings.add("mjs", "text/javascript");
      factory.setMimeMappings(mappings);
    };
  }
}
