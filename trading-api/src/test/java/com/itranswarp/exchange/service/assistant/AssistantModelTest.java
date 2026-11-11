/* 供应商兼容回归：只检查纯请求和假响应，禁止测试时使用真实密钥或发起收费请求。 */
package com.itranswarp.exchange.service.assistant;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.Map;

class AssistantModelTest {
  /** DeepSeek 默认关闭思考，其他域名不擅自加入供应商扩展，显式配置可覆盖。 */
  @Test void deepSeekThinkingIsDisabledOnlyWhenApplicable(){var deepseek=new HttpAiModelClient(true,"https://api.deepseek.com/chat/completions","test","test-only-key");assertEquals(Map.of("type","disabled"),deepseek.requestBody("账户").get("thinking"));assertEquals(64,deepseek.requestBody("账户").get("max_tokens"));var other=new HttpAiModelClient(true,"https://provider.example/chat/completions","test","test-only-key");assertFalse(other.requestBody("账户").containsKey("thinking"));var override=new HttpAiModelClient(true,"https://api.deepseek.com/chat/completions","test","test-only-key",false);assertFalse(override.requestBody("账户").containsKey("thinking"));}
  /** 403/404/429/截断/空答案分别降级，错误正文里即使包含密钥也不能回显。 */
  @Test void failuresHaveDistinctSafeCodes() throws Exception {var model=new HttpAiModelClient(false,"","","");for(var entry:Map.of(403,"MODEL_ACCESS_DENIED",404,"MODEL_ENDPOINT_OR_NAME_NOT_FOUND",429,"MODEL_RATE_LIMITED",500,"MODEL_HTTP_ERROR").entrySet())assertEquals(entry.getValue(),assertThrows(AssistantModelException.class,()->model.content(entry.getKey(),"secret-test-key")).code());assertEquals("MODEL_TRUNCATED",assertThrows(AssistantModelException.class,()->model.content(200,"{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}]}")).code());assertEquals("MODEL_EMPTY_CONTENT",assertThrows(AssistantModelException.class,()->model.content(200,"{\"choices\":[]}")).code());assertEquals("FREEZE",model.content(200,"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"FREEZE\"}}]}"));}
}
