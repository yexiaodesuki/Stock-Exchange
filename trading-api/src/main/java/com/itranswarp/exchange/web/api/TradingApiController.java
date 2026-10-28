/* 证券 API：身份取自签名，现金回源引擎，行情缓存失败回 MySQL；不暴露配给命令。 */
package com.itranswarp.exchange.web.api;

import com.fasterxml.jackson.databind.*;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.*;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.output.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class TradingApiController {
  private final SecurityCatalog catalog;
  private final com.itranswarp.exchange.service.ApiAuthenticationService authentication;
  private final com.itranswarp.exchange.service.TradingEngineApiProxyService engineProxy;
  private final com.itranswarp.exchange.service.HistoryService historyService;
  private final com.itranswarp.exchange.service.MarketQueryService marketQueries;
  private final com.itranswarp.exchange.service.SendEventService sender;

  /** 应用正常装配使用构造器注入，控制器不创建存储或消息客户端。 */
  public TradingApiController(
      SecurityCatalog catalog,
      com.itranswarp.exchange.service.ApiAuthenticationService authentication,
      com.itranswarp.exchange.service.TradingEngineApiProxyService engineProxy,
      com.itranswarp.exchange.service.HistoryService historyService,
      com.itranswarp.exchange.service.MarketQueryService marketQueries,
      com.itranswarp.exchange.service.SendEventService sender) {
    this.catalog = catalog;
    this.authentication = authentication;
    this.engineProxy = engineProxy;
    this.historyService = historyService;
    this.marketQueries = marketQueries;
    this.sender = sender;
  }

  /** 目录可公开读取，不生成任何用户资产。 */
  @GetMapping("/securities")
  public Object securities() {
    return catalog.all();
  }

  /** 账户必须是本人；不允许 URL/请求体指定另一用户标识。 */
  @GetMapping("/account")
  public Object account(@RequestHeader(value = "Authorization", required = false) String auth) {
    return WireJson.tree(engineProxy.internal("/accounts/" + authentication.user(auth)));
  }

  /** 活动订单来自已就绪引擎，历史不能冒充当前挂单。 */
  @GetMapping("/orders")
  public Object orders(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestParam long securityId) {
    catalog.require(securityId);
    return WireJson.tree(
        engineProxy.internal(
            "/accounts/" + authentication.user(auth) + "/orders?securityId=" + securityId));
  }

  /** 历史只过滤本人的指定证券，包含成交和撤销最终状态。 */
  @GetMapping("/history/orders")
  public Object history(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestParam long securityId,
      @RequestParam(defaultValue = "100") int limit) {
    return historyService.history(authentication.user(auth), securityId, limit);
  }

  /** 本人的订单才可读取成交明细；不向客户端暴露对手用户标识。 */
  @GetMapping("/orders/{order}/matches")
  public Object matches(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @PathVariable long order,
      @RequestParam long securityId) {
    return historyService.matches(authentication.user(auth), order, securityId);
  }

  /** 盘口比较该证券版本而非全局成交位置，旧缓存不能覆盖撤单后的盘口。 */
  @GetMapping("/securities/{security}/orderbook")
  public Object book(@PathVariable long security) throws Exception {
    return marketQueries.book(security);
  }

  /** 最近成交优先缓存，缓存异常时从持久 Tick 回源。 */
  @GetMapping("/securities/{security}/ticks")
  public Object ticks(@PathVariable long security, @RequestParam(defaultValue = "100") int limit)
      throws Exception {
    return marketQueries.ticks(security, limit);
  }

  /** 长区间/超过缓存窗口请求回 MySQL，日线不会错误读取小时表。 */
  @GetMapping("/securities/{security}/bars/{period}")
  public Object bars(
      @PathVariable long security,
      @PathVariable String period,
      @RequestParam(defaultValue = "0") long from,
      @RequestParam(defaultValue = "9223372036854775807") long to,
      @RequestParam(defaultValue = "200") int limit)
      throws Exception {
    return marketQueries.bars(security, period, from, to, limit);
  }

  public record OrderBody(
      long securityId, Direction direction, BigDecimal price, BigDecimal quantity) {}

  /** 下单只允许已认证用户，通过服务器绑定的幂等键提交；不接受 userId 或配给字段。 */
  @PostMapping("/orders")
  public ResponseEntity<?> place(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody OrderBody body) {
    long user = authentication.user(auth);
    var command =
        new PlaceOrder(body.securityId(), body.direction(), body.price(), body.quantity());
    command.validate(catalog);
    return sender.submit(user, key, command);
  }

  /** 撤单证券必填，最终归属由引擎再次检查，不信任 HTTP 预检。 */
  @PostMapping("/orders/{order}/cancel")
  public ResponseEntity<?> cancel(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("Idempotency-Key") String key,
      @PathVariable long order,
      @RequestParam long securityId) {
    catalog.require(securityId);
    return sender.submit(authentication.user(auth), key, new CancelOrder(securityId, order));
  }

  /** 本人请求才可查询，持久输出存在且引擎已应用才返回最终结果。 */
  @GetMapping("/requests/{request}")
  public ResponseEntity<?> result(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @PathVariable String request) {
    return sender.result(authentication.user(auth), request);
  }

  /** 只给已认证用户签一次性连接票据，Redis 不可用时明确失败。 */
  @PostMapping("/ws-ticket")
  public Object ticket(@RequestHeader(value = "Authorization", required = false) String auth) {
    return authentication.ticket(auth);
  }

  /** API 健康需要核心可服务，Redis 是可降级缓存，不作为账户健康的唯一依据。 */
  public boolean ready() {
    return engineProxy.ready();
  }

  /** 参数错误明确返回 400，缓存/数据库故障不冒充正常空数组。 */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> invalid(IllegalArgumentException failure) {
    return ResponseEntity.badRequest().body(Map.of("error", "INVALID_PARAMETER"));
  }

  /** 未分类依赖故障保持不可用，不泄露内部堆栈或凭据。 */
  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public ResponseEntity<?> storageFailure() {
    return ResponseEntity.status(503).body(Map.of("error", "STORAGE_UNAVAILABLE"));
  }

  /** 校验失败不能把持久结果说成执行成功，明确不可用并交由后台诊断。 */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<?> invalidState() {
    return ResponseEntity.status(503).body(Map.of("error", "STATE_UNAVAILABLE"));
  }
}
