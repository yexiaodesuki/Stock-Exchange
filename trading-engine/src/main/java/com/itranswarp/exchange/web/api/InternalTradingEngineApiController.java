/* 本机证券核心只读验收端点；不是用户 API，不提供可绕过认证的写资金接口。 */
package com.itranswarp.exchange.web.api;

import com.itranswarp.exchange.TradingEngineService;
import com.itranswarp.exchange.securities.SecurityCatalog;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/securities")
public final class InternalTradingEngineApiController {
  private final TradingEngineService runtime;
  private final SecurityCatalog catalog;

  /** 注入只读运行时视图，不持有公开的事件写入能力。 */
  public InternalTradingEngineApiController(TradingEngineService runtime, SecurityCatalog catalog) {
    this.runtime = runtime;
    this.catalog = catalog;
  }

  /** 状态可在恢复期间读取；未就绪返回 503 而非假正常。 */
  @GetMapping("/status")
  public ResponseEntity<?> status() {
    var versions = new java.util.TreeMap<String, Long>();
    if (runtime.status() == TradingEngineService.Status.READY)
      runtime
          .state()
          .markets()
          .forEach(m -> versions.put(Long.toString(m.securityId()), m.lastSequenceId()));
    return ResponseEntity.status(runtime.status() == TradingEngineService.Status.READY ? 200 : 503)
        .body(
            Map.of(
                "state",
                runtime.status(),
                "applied",
                runtime.applied(),
                "target",
                runtime.target(),
                "marketVersions",
                versions));
  }

  /** 查询用户共享现金和五种持仓；此本机内部端点在第三阶段接正式鉴权。 */
  @GetMapping("/accounts/{user}")
  public Object account(@PathVariable long user) {
    return runtime.account(user);
  }

  /** 聚合价量供 API 回源，不包含订单身份。 */
  @GetMapping("/books/{security}")
  public Object book(@PathVariable long security) {
    return runtime.book(security);
  }

  /** 私人活动订单供已鉴权 API 查询，仍只监听本机内部端口。 */
  @GetMapping("/accounts/{user}/orders")
  public Object orders(@PathVariable long user, @RequestParam long securityId) {
    if (user <= 1) throw new IllegalArgumentException("用户无效");
    catalog.require(securityId);
    return runtime.state().orders().stream()
        .filter(o -> o.userId() == user && o.securityId() == securityId)
        .toList();
  }

  /** 查询单证券活动订单和市场状态，不返回其他证券订单。 */
  @GetMapping("/markets/{security}")
  public Object market(@PathVariable long security) {
    catalog.require(security);
    var value = runtime.state();
    return Map.of(
        "sequenceId",
        value.sequenceId(),
        "market",
        value.markets().stream().filter(m -> m.securityId() == security).findFirst().orElseThrow(),
        "orders",
        value.orders().stream().filter(o -> o.securityId() == security).toList());
  }

  /** 恢复或故障必须阻止查询，不能返回 {} 让页面误以为账户没有资产。 */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<?> unavailable(IllegalStateException failure) {
    return ResponseEntity.status(503)
        .body(Map.of("error", "ENGINE_UNAVAILABLE", "state", runtime.status()));
  }

  /** 非法用户或证券明确返回参数错误，不泄露异常堆栈。 */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> invalid(IllegalArgumentException failure) {
    return ResponseEntity.badRequest().body(Map.of("error", "INVALID_PARAMETER"));
  }
}
