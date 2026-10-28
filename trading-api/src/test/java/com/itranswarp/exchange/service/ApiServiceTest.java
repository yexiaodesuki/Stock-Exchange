/* API 分层回归：真实本机 HTTP 保持精确数值和错误映射；签名身份校验不访问存储。 */
package com.itranswarp.exchange.service;

import static org.junit.jupiter.api.Assertions.*;

import com.itranswarp.exchange.securities.output.SecuritiesTokens;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ApiServiceTest {
  /** 代理抽出后不能退化为 double，也不能把依赖失败伪装成空结果。 */
  @Test
  void proxyPreservesDecimalsAndUnavailableStatus() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // 仅供本测试的临时端口，响应不包含用户私密信息。
    server.createContext(
        "/internal/securities",
        exchange -> {
          byte[] body = "{\"available\":123456789.123456789}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(
              exchange.getRequestURI().getPath().endsWith("failure") ? 503 : 200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    try {
      var proxy =
          new TradingEngineApiProxyService("http://127.0.0.1:" + server.getAddress().getPort());
      assertEquals(
          new BigDecimal("123456789.123456789"),
          proxy.internal("/accounts/2").path("available").decimalValue());
      assertTrue(proxy.ready());
      var failure = assertThrows(ResponseStatusException.class, () -> proxy.internal("/failure"));
      assertEquals(503, failure.getStatusCode().value());
    } finally {
      server.stop(0);
    }
  }

  /** 服务拆分不能让 URL/无效 Bearer 绕过原签名验证。 */
  @Test
  void authenticationStillRequiresValidSignedIdentity() {
    var tokens = new SecuritiesTokens("unit-test-only-key-with-at-least-32-characters");
    var authentication = new ApiAuthenticationService(tokens, null);
    assertEquals(
        2, authentication.user("Bearer " + tokens.issue(2, System.currentTimeMillis() + 60000)));
    assertEquals(
        401,
        assertThrows(ResponseStatusException.class, () -> authentication.user(null))
            .getStatusCode()
            .value());
    assertEquals(
        401,
        assertThrows(ResponseStatusException.class, () -> authentication.user("Bearer invalid"))
            .getStatusCode()
            .value());
  }
}
