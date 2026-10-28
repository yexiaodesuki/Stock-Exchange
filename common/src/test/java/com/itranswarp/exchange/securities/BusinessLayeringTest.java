/* 业务分层约束：控制器不直接访问数据库/消息客户端，领域逻辑不混入 I/O，并保留提交顺序。 */
package com.itranswarp.exchange.securities;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessLayeringTest {
  /** 与 Maven/隔离运行器使用同一项目根目录规则。 */
  private Path root() {
    String path = System.getenv("SECURITIES_TEST_ROOT");
    return path == null ? Path.of("..").toAbsolutePath().normalize() : Path.of(path);
  }

  /** 读取项目源码用于边界检查，不执行应用或连接容器。 */
  private String source(String path) throws Exception {
    return Files.readString(root().resolve(path));
  }

  /** 数据库、网络和消息发送必须留在服务层，HTTP 控制器只做协议适配。 */
  @Test
  void apiControllerDoesNotOwnInfrastructureClients() throws Exception {
    String controller =
        source(
            "trading-api/src/main/java/com/itranswarp/exchange/web/api/TradingApiController.java");
    for (String forbidden :
        List.of(
            "JdbcTemplate", "KafkaProducer", "HttpClient", "queryForList", "TransactionTemplate"))
      assertFalse(controller.contains(forbidden), forbidden);
    for (String required :
        List.of(
            "HistoryService", "SendEventService", "MarketQueryService", "ApiAuthenticationService"))
      assertTrue(controller.contains(required), required);
  }

  /** 领域服务仅处理传入候选，不能自行访问存储、启动线程或发送消息。 */
  @Test
  void domainServicesRemainDeterministic() throws Exception {
    String base = "trading-engine/src/main/java/com/itranswarp/exchange/";
    for (String file :
        List.of(
            "assets/AssetService.java",
            "order/OrderService.java",
            "match/MatchEngine.java",
            "clearing/ClearingService.java")) {
      String text = source(base + file);
      for (String forbidden :
          List.of(
              "JdbcTemplate",
              "KafkaProducer",
              "HttpClient",
              "new Thread",
              "System.currentTimeMillis"))
        assertFalse(text.contains(forbidden), file + ":" + forbidden);
    }
    String runtime = source(base + "TradingEngineService.java");
    int prepare = runtime.indexOf("engine.prepare("),
        persist = runtime.indexOf("outputs.save("),
        install = runtime.indexOf("engine.install(");
    assertTrue(prepare >= 0 && prepare < persist && persist < install, "准备、持久化、安装顺序不能改变");
  }

  /** 推送的会话与 Redis 订阅分离，行情的持久投影与发布分离。 */
  @Test
  void backgroundServicesHaveSeparateBoundaries() throws Exception {
    String sessions =
        source("push/src/main/java/com/itranswarp/exchange/push/web/PushVerticle.java");
    String subscription =
        source("push/src/main/java/com/itranswarp/exchange/push/PushService.java");
    assertFalse(sessions.contains("connectPubSub"));
    assertFalse(subscription.contains("ServerWebSocket"));
    assertTrue(
        source("quotation/src/main/java/com/itranswarp/exchange/quotation/QuotationService.java")
            .contains("database.catchUp"));
    assertTrue(
        source("quotation/src/main/java/com/itranswarp/exchange/quotation/QuotationService.java")
            .contains("publisher.refresh"));
  }
}
