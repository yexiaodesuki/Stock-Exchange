/* 配置中心统一入口：从 config-repo 分发非敏感证券配置，不参与交易和用户配资。 */
package com.itranswarp.exchange;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Spring config server. This project is only used to serve config files.
 *
 * <p>NOTE the default port of config server is 8888.
 */
@EnableConfigServer
@SpringBootApplication
public class ConfigApplication {

  /** 启动本地配置服务，业务客户端必须在该服务可用后启动。 */
  public static void main(String[] args) {
    SpringApplication.run(ConfigApplication.class, args);
  }
}
