/* UI 安全过滤器：独立维护 CSP、CSRF 和请求大小边界，页面与接口不改变。 */
package com.itranswarp.exchange.ui.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.net.URI;
import java.util.Set;

public final class UISecurityFilter implements Filter {
  private final String connectOrigin;

  /** 只接受配置中的明确 WebSocket 地址，保留原地址校验。 */
  public UISecurityFilter(String websocket) {
    URI ws = URI.create(websocket);
    if (!Set.of("ws", "wss").contains(ws.getScheme())
        || ws.getHost() == null
        || ws.getRawQuery() != null
        || ws.getFragment() != null
        || ws.getUserInfo() != null) throw new IllegalArgumentException("推送地址无效");
    connectOrigin = ws.getScheme() + "://" + ws.getRawAuthority();
  }

  /** 写请求先检查会话 CSRF 和大小，再交给控制器；所有响应带原安全头。 */
  @Override
  public void doFilter(ServletRequest raw, ServletResponse out, FilterChain chain)
      throws java.io.IOException, ServletException {
    var request = (HttpServletRequest) raw;
    var response = (HttpServletResponse) out;
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' "
            + connectOrigin
            + "; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action"
            + " 'self'");
    if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
      var session = request.getSession(false);
      String expected = session == null ? null : (String) session.getAttribute("csrf"),
          actual = request.getHeader("X-CSRF-Token");
      if (expected == null
          || actual == null
          || !java.security.MessageDigest.isEqual(
              expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
              actual.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
        response.sendError(403);
        return;
      }
      if (request.getContentLengthLong() > 4096) {
        response.sendError(413);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
