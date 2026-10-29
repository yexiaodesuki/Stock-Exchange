/* 模拟 USD 入金接口：只接受金额和幂等键，身份来自签名，不接入真实支付或通用配资。 */
package com.itranswarp.exchange.web.api;

import com.itranswarp.exchange.service.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/deposits")
public final class DepositController {
  private final ApiAuthenticationService authentication;
  private final DepositService service;
  public record Body(String amount) {}

  /** 只做身份绑定和 HTTP 映射，不持有数据库或消息生产者。 */
  public DepositController(ApiAuthenticationService authentication,DepositService service) {
    this.authentication=authentication;this.service=service;
  }

  /** 202 仅代表持久受理，重复申请返回原记录，状态字段才表示是否到账。 */
  @PostMapping
  public ResponseEntity<?> create(@RequestHeader(value="Authorization",required=false) String auth,
      @RequestHeader("Idempotency-Key") String key,@RequestBody Body body) {
    var value=service.create(authentication.user(auth),key,body.amount());
    return ResponseEntity.status(value.status().equals("PENDING")?202:200).body(value);
  }

  /** 本人分页历史最大 100 条，默认最近 20 条。 */
  @GetMapping
  public Object list(@RequestHeader(value="Authorization",required=false) String auth,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int limit) {
    return service.list(authentication.user(auth),page,limit);
  }

  /** 所有者条件随查询传入，外人无法读取该入金记录。 */
  @GetMapping("/{id}")
  public Object get(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id) {
    return service.get(authentication.user(auth),id);
  }

  /** 仅登录用户读取模拟开关与额度，不公开任何配置密钥。 */
  @GetMapping("/policy")
  public Object policy(@RequestHeader(value="Authorization",required=false) String auth) {
    authentication.user(auth);
    return service.policy();
  }

  /** 参数错误不进入发件箱，只返回业务提示，不回传凭据或 SQL。 */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> invalid(IllegalArgumentException failure) {
    return ResponseEntity.badRequest().body(Map.of("message",failure.getMessage()));
  }

  /** 额度及开关拒绝保留状态码，让页面停止无意义重试。 */
  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  public ResponseEntity<?> rejected(org.springframework.web.server.ResponseStatusException failure) {
    return ResponseEntity.status(failure.getStatusCode()).body(Map.of("message",failure.getReason()==null?"无权访问该入金记录":failure.getReason()));
  }

  /** 数据库故障不冒充成功，客户端必须保留原幂等键。 */
  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public ResponseEntity<?> storageFailure() {
    return ResponseEntity.status(503).body(Map.of("message","入金服务暂不可用，请保留原申请重试"));
  }
}
