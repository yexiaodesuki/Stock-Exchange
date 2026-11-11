/* API 身份与票据服务：验证签名、签发本人短时票据，不接受客户端指定账户身份。 */
package com.itranswarp.exchange.service;

import com.itranswarp.exchange.securities.output.MarketCache;
import com.itranswarp.exchange.securities.output.SecuritiesTokens;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ApiAuthenticationService {
  private final SecuritiesTokens tokens;
  private final MarketCache cache;

  /** 注入签名与一次性票据存储，不产生业务交易。 */
  public ApiAuthenticationService(SecuritiesTokens tokens, MarketCache cache) {
    this.tokens = tokens;
    this.cache = cache;
  }

  /** 身份只来自服务端签名，失败保持原 401。 */
  public long user(String auth) {
    try {
      if (auth == null || !auth.startsWith("Bearer ")) throw new IllegalArgumentException();
      return tokens.verify(auth.substring(7));
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  /** 签发本人短时票据；Redis 不可用仍返回 503。 */
  public Object ticket(String auth) {
    long user = user(auth);
    try {
      return Map.of(
          "ticket", cache.ticket(user, tokens.expires(auth.substring(7))), "expiresIn", 30);
    } catch (RuntimeException unavailable) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "推送票据暂不可用");
    }
  }
}
