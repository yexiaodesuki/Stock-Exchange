/* 助手公开入口：签名绑定本人身份，只有查询和解释，不提供通用工具执行接口。 */
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
}
