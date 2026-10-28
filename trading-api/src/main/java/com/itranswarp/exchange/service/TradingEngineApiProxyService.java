/* 引擎只读代理：固定内部上游，保持原超时、精确金额解析和不可用状态映射。 */
package com.itranswarp.exchange.service;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class TradingEngineApiProxyService {
  private final String engine;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper json =
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  /** 保存内部只读地址，不接受浏览器提供上游 URL。 */
  public TradingEngineApiProxyService(String engine) {
    this.engine = engine;
  }

  /** 只读请求保留原超时和 503 映射，恢复中不返回伪空账户。 */
  public JsonNode internal(String path) {
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(engine + "/internal/securities" + path))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IllegalStateException();
      return json.readTree(response.body());
    } catch (Exception unavailable) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "证券引擎未就绪");
    }
  }

  /** 健康依赖引擎业务就绪，而非 HTTP 端口存活。 */
  public boolean ready() {
    try {
      internal("/status");
      return true;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }
}
