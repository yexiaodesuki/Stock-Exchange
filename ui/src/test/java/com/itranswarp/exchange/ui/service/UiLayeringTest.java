/* UI 后端分层回归：保持同一会话 CSRF 和原注册/登录限流阈值，页面资源不变。 */
package com.itranswarp.exchange.ui.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;

class UiLayeringTest {
  /** 同一会话不能每次读取都换 CSRF，不同会话不共享防伪令牌。 */
  @Test
  void csrfRemainsStableAndSessionSpecific() {
    var session = new MockHttpSession();
    String first = SessionService.csrf(session);
    assertEquals(first, SessionService.csrf(session));
    assertNotEquals(first, SessionService.csrf(new MockHttpSession()));
  }

  /** 独立服务仍在第十六次请求拒绝，且不连带拒绝其他地址。 */
  @Test
  void rateLimitKeepsOriginalThreshold() {
    var service = new SessionService();
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.2");
    for (int attempt = 0; attempt < 15; attempt++) service.rate(request);
    assertEquals(
        429,
        assertThrows(ResponseStatusException.class, () -> service.rate(request))
            .getStatusCode()
            .value());
    request.setRemoteAddr("127.0.0.3");
    assertDoesNotThrow(() -> service.rate(request));
  }
}
