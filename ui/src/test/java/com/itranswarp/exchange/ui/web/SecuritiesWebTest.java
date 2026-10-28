/* UI 安全边界单元测试：路由白名单、匿名 CSRF 和响应策略，无数据库或真实资金副作用。 */
package com.itranswarp.exchange.ui.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class SecuritiesWebTest {
  /** 代理只接受新证券接口，不允许内部转账或编码穿越路径。 */
  @Test
  void restrictsProxyRoutes() {
    assertTrue(MvcController.allowed("/api/v1/orders", true));
    assertTrue(MvcController.allowed("/api/v1/requests/u1000:abcd", false));
    assertFalse(MvcController.allowed("/internal/transfer", true));
    assertFalse(MvcController.allowed("/api/v1/securities/../internal", false));
    assertFalse(MvcController.allowed("/api/v1/account", true));
    assertFalse(MvcController.allowed("/api/v1/requests/u1000%3Aabcd", false));
  }

  /** 登录也不能免除 CSRF，避免把攻击者身份注入他人的浏览器会话。 */
  @Test
  void rejectsWritesWithoutCsrf() throws Exception {
    var web =
        new MvcController(null, null, null, "http://127.0.0.1:8101", "ws://localhost:8106/ws");
    var request = new MockHttpServletRequest("POST", "/auth/signin");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    web.doFilter(request, response, chain);
    assertEquals(403, response.getStatus());
    assertNull(chain.getRequest());
  }

  /** 校验成功才进入控制器，同时阻止缓存和第三方脚本注入。 */
  @Test
  void acceptsOnlyMatchingSessionCsrf() throws Exception {
    var web =
        new MvcController(null, null, null, "http://127.0.0.1:8101", "ws://localhost:8106/ws");
    var request = new MockHttpServletRequest("POST", "/api/v1/orders");
    request.getSession().setAttribute("csrf", "random-session-proof");
    request.addHeader("X-CSRF-Token", "random-session-proof");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    web.doFilter(request, response, chain);
    assertNotNull(chain.getRequest());
    assertEquals("no-store", response.getHeader("Cache-Control"));
    assertTrue(response.getHeader("Content-Security-Policy").contains("script-src 'self'"));
    assertFalse(response.getHeader("Content-Security-Policy").contains("unsafe-inline"));
  }

  /** 过大请求不进入 JSON 解析或口令哈希，避免无限制资源消耗。 */
  @Test
  void rejectsLargeWritesBeforeController() throws Exception {
    var web =
        new MvcController(null, null, null, "http://127.0.0.1:8101", "ws://localhost:8106/ws");
    var request = new MockHttpServletRequest("POST", "/auth/signup");
    request.getSession().setAttribute("csrf", "proof");
    request.addHeader("X-CSRF-Token", "proof");
    request.setContent(new byte[4097]);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    web.doFilter(request, response, chain);
    assertEquals(413, response.getStatus());
    assertNull(chain.getRequest());
  }
}
