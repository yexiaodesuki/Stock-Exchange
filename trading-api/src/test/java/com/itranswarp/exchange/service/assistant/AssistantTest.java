/* 助手回归：假模型与本机只读上游，验证精确金额、拒绝虚构输出和限流资源释放，无收费调用。 */
package com.itranswarp.exchange.service.assistant;
import static org.junit.jupiter.api.Assertions.*;
import com.itranswarp.exchange.service.TradingEngineApiProxyService;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AssistantTest {
  /** 使用与演示目录相同的五证券规则，名称解析单测不访问开发数据库。 */
  static AssistantSecurityResolver resolver(){var definitions=new ArrayList<com.itranswarp.exchange.securities.SecurityDefinition>();String[] symbols={"STAR","GREEN","CARE","DAILY","SAIL"},names={"星河科技","青山能源","安康医药","优选消费","远航工业"};for(int i=0;i<5;i++)definitions.add(new com.itranswarp.exchange.securities.SecurityDefinition(1001+i,symbols[i],names[i],com.itranswarp.exchange.securities.Currency.USD,new java.math.BigDecimal("0.01"),java.math.BigDecimal.ONE,com.itranswarp.exchange.securities.SecurityStatus.ACTIVE,1));return new AssistantSecurityResolver(new com.itranswarp.exchange.securities.SecurityCatalog(definitions));}
  /** 不认识的模型输出或含金额的自由文本不能成为展示解释；写操作只能拒绝。 */
  @Test void onlyWhitelistedIntentsAreAccepted(){assertThrows(IllegalArgumentException.class,()->AssistantRules.validate("ACCOUNT 余额99999"));assertEquals(AssistantRules.Intent.UNSUPPORTED,AssistantRules.classify("帮我充值"));assertEquals(AssistantRules.Intent.UNSUPPORTED,AssistantRules.classify("查询user2账户"));assertFalse(new HttpAiModelClient(false,"","","").configured());}
  /** 高精度字段不经过 double；模型故障仍返回原事实并标记规则降级。 */
  @Test void factsAreExactAndModelFailureFallsBack() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    // 记录精确本人路径；不提供业务写端点。
    server.createContext("/internal/securities/accounts/42/assistant-context",exchange->{byte[] body="{\"sequenceId\":\"100\",\"cash\":[{\"available\":999999999999.99,\"frozen\":50}],\"positions\":[],\"orders\":[{\"securityId\":1001,\"quantity\":10,\"direction\":\"BUY\",\"price\":10,\"remaining\":5}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});server.start();
    try {
      var reads=new AssistantReadService(new TradingEngineApiProxyService("http://127.0.0.1:"+server.getAddress().getPort()),null,null);
      AiModelClient bad=new AiModelClient(){/** 模拟服务故障，不访问网络。 */public String classify(String question){throw new IllegalStateException("timeout");}/** 测试开启分类路径。 */public boolean configured(){return true;}};
      var service=new AssistantService(reads,bad,resolver());
      var response=(Map<?,?>)service.explain(42,new AssistantService.Question("为什么资金冻结",null,null,null));
      assertEquals("RULES",response.get("mode"));assertEquals("MODEL_NETWORK_OR_RESPONSE_ERROR",response.get("fallbackReason"));
      var facts=(Map<?,?>)response.get("facts");assertEquals("50",facts.get("calculatedBuyFrozen"));assertTrue(facts.get("cash").toString().contains("\"999999999999.99\""));
      assertTrue(response.get("highlights").toString().contains("999999999999.99"));
      AiModelClient broad=new AiModelClient(){/** 故意返回过宽分类，不能覆盖明确冻结意图。 */public String classify(String q){return "ACCOUNT";}/** 测试模型启用路径。 */public boolean configured(){return true;}};
      var modelService=new AssistantService(reads,broad,resolver());
      assertEquals("FREEZE",((Map<?,?>)modelService.explain(42,new AssistantService.Question("为什么资金冻结",null,null,null))).get("intent"));
      assertEquals("ACCOUNT",((Map<?,?>)modelService.explain(42,new AssistantService.Question("我还有多少钱可以用？",null,null,null))).get("intent"));
      assertThrows(ResponseStatusException.class,()->service.explain(43,new AssistantService.Question("账户余额",null,null,null)));
    }finally{server.stop(0);}
  }
  /** 同用户并发快速拒绝，异常路径也释放许可，不能积压无限模型任务。 */
  @Test void limitsAndReleasesConcurrency() throws Exception {
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    AiModelClient model=new AiModelClient(){/** 测试阻塞和非法输出，不发请求。 */public String classify(String question)throws Exception{entered.countDown();release.await(3,TimeUnit.SECONDS);return "UNSUPPORTED";}/** 强制走模型路径。 */public boolean configured(){return true;}};
    var service=new AssistantService(null,model,resolver());var executor=Executors.newSingleThreadExecutor();
    try{var result=executor.submit(()->service.explain(42,new AssistantService.Question("你好",null,null,null)));assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(429,assertThrows(ResponseStatusException.class,()->service.explain(42,new AssistantService.Question("你好",null,null,null))).getStatusCode().value());release.countDown();result.get(3,TimeUnit.SECONDS);assertDoesNotThrow(()->service.explain(42,new AssistantService.Question("帮我充值",null,null,null)));}finally{release.countDown();executor.shutdownNow();}
  }
  /** 全名、短名、英文代码和全部范围可识别，多目标或未知证券不能被当成全部数据。 */
  @Test void namesAliasesAndAmbiguity(){var resolver=resolver();for(String q:List.of("星河科技的订单","星河的委托","star的订单"))assertEquals(1001L,resolver.resolve(q,null).securityId());assertEquals(1002L,resolver.resolve("青山的持仓",null).securityId());assertEquals(1003L,resolver.resolve("安康的订单",null).securityId());assertEquals(1004L,resolver.resolve("DAILY的订单",null).securityId());assertEquals(1005L,resolver.resolve("远航的订单",null).securityId());assertNull(resolver.resolve("我的所有订单",null).securityId());assertTrue(resolver.resolve("我的所有订单",null).clarification().isEmpty());assertFalse(resolver.resolve("星河和青山的订单",null).clarification().isEmpty());assertFalse(resolver.resolve("苹果的订单",null).clarification().isEmpty());assertFalse(resolver.resolve("START的订单",null).clarification().isEmpty());}
  /** 安全拒绝、歧义和未知问法分别标识，拒绝问题不产生模型费用。 */
  @Test void refusesAndClarifiesWithoutCallingModel(){var calls=new java.util.concurrent.atomic.AtomicInteger();AiModelClient model=new AiModelClient(){/** 记录调用次数，返回未知意图。 */public String classify(String q){calls.incrementAndGet();return "UNSUPPORTED";}/** 用配置成功模拟线上情况。 */public boolean configured(){return true;}};var service=new AssistantService(null,model,resolver());var refusal=(Map<?,?>)service.explain(42,new AssistantService.Question("查询user2账户",null,null,null));assertEquals("SAFETY_REFUSAL",refusal.get("mode"));var ambiguous=(Map<?,?>)service.explain(42,new AssistantService.Question("星河和青山的订单",null,null,null));assertEquals("CLARIFICATION",ambiguous.get("mode"));assertEquals(0,calls.get());var unsupported=(Map<?,?>)service.explain(42,new AssistantService.Question("你好",null,null,null));assertEquals("QUESTION_NOT_SUPPORTED",unsupported.get("fallbackReason"));assertEquals(1,calls.get());}
}
