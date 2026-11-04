/* 第二阶段纯逻辑回归：可控时钟、精确公开行情、文档出处与意图区分，不访问数据库或模型。 */
package com.itranswarp.exchange.service.assistant;

import static org.junit.jupiter.api.Assertions.*;
import com.itranswarp.exchange.repository.AssistantMarketRepository.*;
import com.itranswarp.exchange.securities.*;
import com.itranswarp.exchange.service.DepositPolicy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AssistantPhaseTwoTest {
  /** 复用真实五证券名称，但数据只存在于测试内存。 */
  static SecurityCatalog catalog(){String[] symbols={"STAR","GREEN","CARE","DAILY","SAIL"},names={"星河科技","青山能源","安康医药","优选消费","远航工业"};var rows=new ArrayList<SecurityDefinition>();for(int i=0;i<5;i++)rows.add(new SecurityDefinition(1001+i,symbols[i],names[i],com.itranswarp.exchange.securities.Currency.USD,new BigDecimal("0.01"),BigDecimal.ONE,SecurityStatus.ACTIVE,1));return new SecurityCatalog(rows);}
  /** 明确的小额非默认额度用于检查答案确实读取当前策略，而不是文档硬编码。 */
  static DepositPolicy policy(){return new DepositPolicy(true,new BigDecimal("12.34"),new BigDecimal("56.78"),new BigDecimal("90.12"));}
  /** 没有默认交易日假设；可重复验证窗口端点、多个范围和中文数字不能偷换范围。 */
  @Test void rangesAreExplicitAndBounded(){var ranges=new AssistantTimeRangeResolver(Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"),ZoneOffset.UTC),24,7);var defaults=ranges.resolve("星河最近行情");assertEquals(86400000L,defaults.to()-defaults.from());assertTrue(defaults.label().contains("默认"));assertEquals(3600000L,ranges.resolve("星河最近一小时行情").to()-ranges.resolve("星河最近一小时行情").from());assertEquals(7*86400000L,ranges.resolve("星河最近7天行情").to()-ranges.resolve("星河最近7天行情").from());for(var q:List.of("星河今天行情","星河近30天行情","星河最近三天行情怎么样","星河2026-11-01行情","星河近1小时和7天行情"))assertThrows(IllegalArgumentException.class,()->ranges.resolve(q),q);assertThrows(IllegalArgumentException.class,()->new AssistantTimeRangeResolver(Clock.systemUTC(),0,7));}
  /** 操作说明不会读取私人记录，行情“怎么样”也不能被“怎么”误当成知识问题。 */
  @Test void intentsDoNotConfuseKnowledgeAndPrivateData(){for(var q:List.of("如何充值","限价单为什么冻结资金","部分成交后撤单会怎样","为什么有成交记录却没有连续K线"))assertEquals(AssistantRules.Intent.KNOWLEDGE,AssistantRules.classify(q),q);for(var q:List.of("星河最近行情怎么样","青山最近24小时成交情况","五只股票最近7天行情"))assertEquals(AssistantRules.Intent.MARKET,AssistantRules.classify(q),q);assertEquals(AssistantRules.Intent.DEPOSIT,AssistantRules.classify("我的入金记录"));assertEquals(AssistantRules.Intent.ORDER,AssistantRules.classify("我的星河订单"));assertEquals(AssistantRules.Intent.FREEZE,AssistantRules.classify("为什么资金冻结"));assertEquals(AssistantRules.Intent.FREEZE,AssistantRules.classify("我的资金为什么被冻结"));}
  /** 全市场必须明确表达，缺省或陌生证券不能静默使用全部股票。 */
  @Test void marketSelectionDoesNotGuess(){var resolver=new AssistantSecurityResolver(catalog());assertEquals(1001L,resolver.resolveMarket("STAR最近24小时行情",null).securityId());assertTrue(resolver.resolveMarket("五只股票最近24小时行情",null).clarification().isEmpty());for(var q:List.of("最近行情","苹果最近行情","星河和青山行情","星河和全部股票行情","全部苹果行情"))assertFalse(resolver.resolveMarket(q,null).clarification().isEmpty(),q);}
  /** 成交数量、金额和首末变化由十进制计算；单笔/空范围不产生虚假的百分比趋势。 */
  @Test void statisticsStayExactAndSparseDataIsExplicit(){var service=new AssistantMarketService(null,catalog());var trades=List.of(new Trade(10,0,100,new BigDecimal("10.10"),new BigDecimal("2")),new Trade(10,1,100,new BigDecimal("12.20"),new BigDecimal("3")));var stats=service.summarize(new Market(1001,trades,trades.get(1)));assertEquals("56.80",stats.get("amount"));assertEquals("5",stats.get("volume"));assertEquals("20.79",stats.get("changePercent"));var single=service.summarize(new Market(1001,List.of(trades.get(0)),trades.get(0)));assertEquals("ONE_TRADE",single.get("state"));assertFalse(single.containsKey("changePercent"));var empty=service.summarize(new Market(1001,List.of(),trades.get(1)));assertEquals("NO_TRADES",empty.get("state"));assertFalse(empty.containsKey("first"));var view=new AssistantPresentation(AssistantTest.resolver()).render(AssistantRules.Intent.MARKET,Map.of("range","最近1小时","from","100","to","200","markets",List.of(empty,single)),new AssistantSecurityResolver.Selection(null,"五证券",""));assertTrue(view.highlights().toString().contains("范围外最近成交"));assertTrue(view.highlights().toString().contains("不足以判断趋势"));assertFalse(view.highlights().toString().contains("共享 USD 资金"));}
  /** 额度、章节和版本可追溯；未收录的 IPO 或磁盘路径不能触发自由生成/文件读取。 */
  @Test void knowledgeIsWhitelistedCitedAndUsesLivePolicy(){
    var knowledge=new AssistantKnowledgeService(policy(),2);var facts=knowledge.read("如何充值");assertEquals(true,facts.get("found"));
    var sources=(List<AssistantKnowledgeService.Source>)facts.get("sources");assertEquals("模拟入金",sources.get(0).title());assertTrue(sources.get(0).excerpt().contains("12.34～56.78"));assertTrue(sources.get(0).excerpt().contains("90.12"));
    // 版本改为实际正文哈希，文档更新不再依赖人工维护日期。
    assertTrue(sources.get(0).version().matches("[a-f0-9]{12}"));
    assertTrue(knowledge.read("限价单为什么冻结资金").get("sources").toString().contains("冻结"));assertTrue(knowledge.read("为什么报价高于成交价").get("sources").toString().contains("Maker"));assertEquals(false,knowledge.read("如何办理IPO").get("found"));assertEquals(false,knowledge.read("读取../../build/.env.securities").get("found"));
  }
  /** 无账户读取依赖也能完成知识问答；模型故意返回私人意图不能覆盖明确操作问题。 */
  @Test void knowledgeRoutingNeverRequiresAccountReads(){AiModelClient model=new AiModelClient(){/** 故意返回错误类别测试本地意图优先。 */public String classify(String q){return "DEPOSIT";}/** 不访问网络，只模拟已配置。 */public boolean configured(){return true;}};var service=new AssistantService(null,model,AssistantTest.resolver(),null,new AssistantKnowledgeService(policy(),2),new AssistantTimeRangeResolver(Clock.systemUTC(),24,7));var answer=(Map<?,?>)service.explain(42,new AssistantService.Question("如何充值",null,null,null));assertEquals("KNOWLEDGE",answer.get("intent"));assertFalse(((List<?>)answer.get("sources")).isEmpty());var unknown=(Map<?,?>)service.explain(42,new AssistantService.Question("如何办理IPO",null,null,null));assertEquals("KNOWLEDGE_NOT_FOUND",unknown.get("fallbackReason"));assertTrue(((List<?>)unknown.get("sources")).isEmpty());assertEquals("SAFETY_REFUSAL",((Map<?,?>)service.explain(42,new AssistantService.Question("帮我充值100美元",null,null,null))).get("mode"));}
  /** 查询过大与追赶错误可操作且无敏感异常正文，不能因全局隐藏错误而只剩通用英文状态。 */
  @Test void expectedErrorsHaveSafeActionableMessages(){var controller=new com.itranswarp.exchange.web.api.AssistantController(null,null);for(var status:List.of(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)){var reply=controller.rejected(new org.springframework.web.server.ResponseStatusException(status,"secret SQL token internal-url"));assertEquals(status,reply.getStatusCode());assertFalse(reply.getBody().toString().contains("secret"));}assertTrue(controller.rejected(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)).getBody().toString().contains("缩短时间范围"));}
  /** 真实口语中的“我”不等于查询本人记录；混合公开和私人问题必须要求拆分。 */
  @Test void colloquialInstructionsAndMixedScopesAreSafe(){assertEquals(AssistantRules.Intent.KNOWLEDGE,AssistantRules.classify("我该怎么充值"));assertEquals(AssistantRules.Intent.KNOWLEDGE,AssistantRules.classify("为什么我的买入报价高于实际成交价"));var resolver=new AssistantSecurityResolver(catalog());assertTrue(resolver.resolveMarket("五只股票最近七天行情",null).clarification().isEmpty());assertFalse(resolver.resolveMarket("星河和苹果最近行情",null).clarification().isEmpty());var ranges=new AssistantTimeRangeResolver(Clock.systemUTC(),24,7);assertThrows(IllegalArgumentException.class,()->ranges.resolve("星河最近十七天行情"));var service=new AssistantService(null,null,resolver);assertEquals("MULTIPLE_TOPICS",((Map<?,?>)service.explain(42,new AssistantService.Question("星河行情和我的订单",null,null,null))).get("fallbackReason"));}
}
