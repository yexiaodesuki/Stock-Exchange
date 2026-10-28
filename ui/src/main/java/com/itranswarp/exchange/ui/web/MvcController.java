/* 证券页面、会话与受限代理：浏览器不持有后端 Bearer，所有写操作验证 CSRF。 */
package com.itranswarp.exchange.ui.web;

import com.itranswarp.exchange.securities.output.SecuritiesTokens;
import com.itranswarp.exchange.ui.demo.DemoBootstrap;
import com.itranswarp.exchange.ui.service.SessionService;
import com.itranswarp.exchange.ui.service.UserService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
public final class MvcController implements Filter {
  private final UserService users;
  private final DemoBootstrap demo;
  private final String websocket;
  private final SessionService sessions = new SessionService();
  private final com.itranswarp.exchange.ui.service.ApiProxyService proxyService;
  private final com.itranswarp.exchange.ui.web.UISecurityFilter securityFilter;

  /** 保留现有控制器与过滤器注册入口，后端职责交给独立组件；不改页面。 */
  public MvcController(
      UserService users,
      SecuritiesTokens tokens,
      DemoBootstrap demo,
      String api,
      String websocket) {
    this.users = users;
    this.demo = demo;
    this.websocket = websocket;
    proxyService = new com.itranswarp.exchange.ui.service.ApiProxyService(api, tokens, demo);
    securityFilter = new com.itranswarp.exchange.ui.web.UISecurityFilter(websocket);
  }

  /** 新页面只引用本地资源，不加载旧 BTC 脚本和登录 Cookie。 */
  @GetMapping({"/", "/signin", "/signup"})
  public String page() {
    return "forward:/securities/index.html";
  }

  /** 首次访问建立匿名会话与 CSRF；用户信息只来自服务端会话。 */
  @GetMapping("/session")
  @ResponseBody
  public Object session(HttpServletRequest request) {
    var session = request.getSession(true);
    return Map.of(
        "csrf",
        SessionService.csrf(session),
        "user",
        Optional.ofNullable(session.getAttribute("user")).orElse(Map.of()),
        "websocket",
        websocket,
        "demo",
        demo.status());
  }

  public record Credentials(String email, String name, String password) {}

  /** 登录成功更换会话标识，防止固定会话；不在日志输出密码或令牌。 */
  @PostMapping("/auth/signin")
  @ResponseBody
  public Object signin(@RequestBody Credentials body, HttpServletRequest request) {
    sessions.rate(request);
    var user = users.authenticate(body.email(), body.password());
    request.changeSessionId();
    request.getSession().setAttribute("user", user);
    request.getSession().setAttribute("csrf", UUID.randomUUID().toString());
    return Map.of("ok", true);
  }

  /** 普通注册余额为零；演示初始化未结束时不允许插入竞争身份。 */
  @PostMapping("/auth/signup")
  @ResponseBody
  public Object signup(@RequestBody Credentials body, HttpServletRequest request) {
    sessions.rate(request);
    demo.requireComplete();
    var user = users.create(body.email(), body.name(), body.password());
    request.changeSessionId();
    request.getSession().setAttribute("user", user);
    request.getSession().setAttribute("csrf", UUID.randomUUID().toString());
    return Map.of("ok", true);
  }

  /** 注销只清除登录身份，不撤销挂单、不重置资金。 */
  @PostMapping("/auth/signout")
  @ResponseBody
  public Object signout(HttpServletRequest request) {
    request.getSession().invalidate();
    return Map.of("ok", true);
  }

  /** 保留上游状态码与十进制 JSON，且始终用当前会话身份替换客户端认证头。 */
  @RequestMapping(
      value = "/api/v1/**",
      method = {RequestMethod.GET, RequestMethod.POST})
  @ResponseBody
  public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws Exception {
    return proxyService.proxy(request);
  }

  /** 路由白名单不含内部资金配给、管理、任意 URL 或旧 BTC 接口。 */
  static boolean allowed(String path, boolean post) {
    return com.itranswarp.exchange.ui.service.ApiProxyService.allowed(path, post);
  }

  /** 所有响应禁用缓存并设同源安全策略；CSRF 在读取写请求正文前验证。 */
  @Override
  public void doFilter(ServletRequest raw, ServletResponse out, FilterChain chain)
      throws java.io.IOException, ServletException {
    securityFilter.doFilter(raw, out, chain);
  }

  /** 参数与登录错误不返回堆栈，前端获得可读提示。 */
  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseBody
  public ResponseEntity<?> invalid(IllegalArgumentException failure) {
    return ResponseEntity.badRequest().body(Map.of("message", failure.getMessage()));
  }

  /** 并发注册邮箱唯一冲突由事务回滚，不能留下半个用户。 */
  @ExceptionHandler(UserService.EmailConflict.class)
  @ResponseBody
  public ResponseEntity<?> duplicate() {
    return ResponseEntity.status(409).body(Map.of("message", "邮箱已被注册"));
  }

  /** 数据库故障不伪装成登录错误或零余额。 */
  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  @ResponseBody
  public ResponseEntity<?> unavailable() {
    return ResponseEntity.status(503).body(Map.of("message", "存储服务暂不可用"));
  }
}
