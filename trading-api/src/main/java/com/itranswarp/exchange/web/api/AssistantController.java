/* 助手公开入口：绑定本人身份，提供只读数据和知识；预期拒绝输出受控提示，不泄露内部异常。 */
package com.itranswarp.exchange.web.api;
import com.itranswarp.exchange.service.ApiAuthenticationService;
import com.itranswarp.exchange.service.assistant.AssistantService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController @RequestMapping("/api/v1/assistant")
public final class AssistantController {
  private final AssistantService service;private final ApiAuthenticationService authentication;
  /** 控制器不保存用户会话，不接收浏览器指定的用户编号。 */
  public AssistantController(AssistantService service,ApiAuthenticationService authentication){this.service=service;this.authentication=authentication;}
  /** 登录后查询是否配置，不产生模型调用。 */
  @GetMapping("/status") public Object status(@RequestHeader(value="Authorization",required=false)String auth){authentication.user(auth);return service.status();}
  /** POST 虽是业务只读，仍经 UI 的 CSRF 保护。 */
  @PostMapping("/explain") public Object explain(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody AssistantService.Question body){return service.explain(authentication.user(auth),body);}
  /** 无效输入明确拒绝，不由模型猜测缺失的目标。 */
  @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(){return ResponseEntity.badRequest().body(Map.of("message","问题或目标无效，请选择本人的记录"));}
  /** 依赖故障保持不可用，不返回伪零余额或模型编造事实。 */
  @ExceptionHandler({org.springframework.dao.DataAccessException.class,IllegalStateException.class}) public ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("message","业务数据暂不可用，无法解释"));}
  /** 范围过大、行情追赶和限流保留状态码；只给固定文案，不把异常原文暴露给页面。 */
  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  public ResponseEntity<?> rejected(org.springframework.web.server.ResponseStatusException failure){
    String message=switch(failure.getStatusCode().value()){
      case 422->"所选范围成交过多，请缩短时间范围后重试";
      case 503->"行情追赶中或业务数据暂不可用，请稍后重试；这不代表没有成交";
      case 429->"助手繁忙，请稍后再问；交易功能不受此助手限流影响";
      case 401->"请先登录后使用助手";
      case 404->"未找到可访问的本人记录";
      default->"暂时无法完成此查询";
    };
    return ResponseEntity.status(failure.getStatusCode()).body(Map.of("message",message));
  }
}
