/* UI 代理服务：固定上游、路由白名单与当前会话身份，不透传浏览器认证头。 */
package com.itranswarp.exchange.ui.service;

import com.itranswarp.exchange.securities.output.SecuritiesTokens;
import com.itranswarp.exchange.ui.demo.DemoBootstrap;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

public final class ApiProxyService {
  private final SecuritiesTokens tokens;
  private final DemoBootstrap demo;
  private final String api;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** 上游只由配置提供，不从请求拼接任意 URL。 */
  public ApiProxyService(String api, SecuritiesTokens tokens, DemoBootstrap demo) {
    this.api = api.replaceAll("/$", "");
    this.tokens = tokens;
    this.demo = demo;
  }

  /** 保留原状态码、请求限制、超时和初始化写保护。 */
  public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws Exception {
    String path = request.getRequestURI();
    boolean post = "POST".equals(request.getMethod());
    if (!allowed(path, post)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var session = request.getSession(false);
    var user = session == null ? null : (UserService.User) session.getAttribute("user");
    boolean publicRead = !post && path.startsWith("/api/v1/securities");
    if (!publicRead && user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (post && !path.equals("/api/v1/ws-ticket")) demo.requireComplete();
    String query = request.getQueryString();
    if (query != null && (query.length() > 600 || !query.matches("[A-Za-z0-9=&._-]+")))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var builder =
        HttpRequest.newBuilder(URI.create(api + path + (query == null ? "" : "?" + query)))
            .timeout(Duration.ofSeconds(9))
            .header("Accept", "application/json");
    if (user != null)
      builder.header(
          "Authorization",
          "Bearer " + tokens.issue(Long.parseLong(user.id()), System.currentTimeMillis() + 600000));
    if (post) {
      String key = request.getHeader("Idempotency-Key");
      if (key != null) {
        if (!key.matches("[A-Za-z0-9._:-]{1,100}"))
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        builder.header("Idempotency-Key", key);
      }
      byte[] body = request.getInputStream().readNBytes(4097);
      if (body.length > 4096) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    } else builder.GET();
    try {
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      return ResponseEntity.status(response.statusCode())
          .contentType(MediaType.APPLICATION_JSON)
          .body(response.body());
    } catch (java.io.IOException | InterruptedException unavailable) {
      if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "交易服务暂不可用");
    }
  }

  /** 仅放行本人模拟入金和证券接口，不暴露通用配资、内部管理和任意转发。 */
  public static boolean allowed(String path, boolean post) {
    return post
        ? path.matches("/api/v1/(orders|orders/[0-9]+/cancel|ws-ticket|deposits)")
        : path.matches(
            "/api/v1/(securities|securities/[0-9]+/(orderbook|ticks|bars/(sec|min|hour|day))|account|orders|history/orders|orders/[0-9]+/matches|requests/[A-Za-z0-9._:-]+|deposits|deposits/policy|deposits/[a-f0-9-]{36})");
  }
}
