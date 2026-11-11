/* 用户知识覆盖回归：离线检查问法分流与来源，不读取账户、不访问模型或向量库。 */
package com.itranswarp.exchange.service.assistant;

import com.itranswarp.exchange.service.DepositPolicy;
import com.itranswarp.exchange.service.assistant.rag.KnowledgeDocumentService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssistantKnowledgeCoverageTest {
  /** 直接加载正式文档，验证典型新增问题即使没有外部模型也能找到对应章节。 */
  @Test void addedQuestionsReachTheirSections() {
    var documents = new KnowledgeDocumentService(new DepositPolicy(true, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN));
    assertEquals(16, documents.sections().size());
    var cases = Map.of(
        "能不能买半股", "限价委托",
        "股票价格最小单位", "限价委托",
        "如何看买盘和卖盘", "盘口",
        "下单超时了", "请求进度",
        "如何查看历史委托成交明细", "历史委托",
        "怎么退出登录", "退出",
        "充值超时", "原申请重试",
        "怎么看引用", "只读助手");
    cases.forEach((question, section) -> {
      assertEquals(AssistantRules.Intent.KNOWLEDGE, AssistantRules.classify(question), question);
      assertTrue(documents.lexical(question, 3).stream().anyMatch(s -> s.title().contains(section)), question);
    });
  }

  /** 已有本人查询及实际行情仍使用实时数据路径，不能被新增知识话题吞掉。 */
  @Test void liveDataQuestionsKeepTheirRoutes() {
    assertEquals(AssistantRules.Intent.ORDER, AssistantRules.classify("我的所有订单"));
    assertEquals(AssistantRules.Intent.DEPOSIT, AssistantRules.classify("我的入金情况"));
    assertEquals(AssistantRules.Intent.ACCOUNT, AssistantRules.classify("我的账户余额"));
    assertEquals(AssistantRules.Intent.MARKET, AssistantRules.classify("星河最近24小时行情"));
  }
}
