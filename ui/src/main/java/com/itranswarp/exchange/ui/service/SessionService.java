/* 会话辅助服务：只处理 CSRF 随机值和原有 IP 限流，不改变登录、注册和资金行为。 */
package com.itranswarp.exchange.ui.service;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class SessionService {
  private final Map<String, long[]> attempts = new HashMap<>();

  /** 匿名与登录会话使用原随机源和字段名，页面协议不变。 */
  public static String csrf(HttpSession session) {
    String value = (String) session.getAttribute("csrf");
    if (value == null) {
      value = Base64.getUrlEncoder().withoutPadding().encodeToString(UserService.random(32));
      session.setAttribute("csrf", value);
    }
    return value;
  }

  /** 保留原五分钟窗口、十五次阈值与有界地址容量。 */
  public synchronized void rate(HttpServletRequest request) {
    long now = System.currentTimeMillis();
    attempts.entrySet().removeIf(entry -> entry.getValue()[0] < now - 300000);
    String address = request.getRemoteAddr();
    long[] value = attempts.get(address);
    if (value == null) {
      if (attempts.size() >= 1024) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
      value = new long[] {now, 0};
      attempts.put(address, value);
    }
    if (++value[1] > 15) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请稍后再试");
  }
}
