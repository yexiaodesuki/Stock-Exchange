/* 应用启动入口：只启动 Spring 并导入专属装配，业务与资源管理不放在入口类。 */
package com.itranswarp.exchange;

import com.itranswarp.exchange.config.SequencerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(SequencerConfiguration.class)
public class SecuritiesSequencerApplication {
  /** 保留原启动模式和配置加载；Push 不启用 Servlet，其他应用沿用自动配置。 */
  public static void main(String[] args) {
    SpringApplication.run(SecuritiesSequencerApplication.class, args);
  }
}
