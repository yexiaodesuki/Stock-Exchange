/* 应用启动入口：只启动 Spring 并导入专属装配，业务与资源管理不放在入口类。 */
package com.itranswarp.exchange;

import com.itranswarp.exchange.config.UIConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(UIConfiguration.class)
public class SecuritiesUIApplication {
  /** 保留原启动模式和配置加载；Push 不启用 Servlet，其他应用沿用自动配置。 */
  public static void main(String[] args) {
    SpringApplication.run(SecuritiesUIApplication.class, args);
  }
}
