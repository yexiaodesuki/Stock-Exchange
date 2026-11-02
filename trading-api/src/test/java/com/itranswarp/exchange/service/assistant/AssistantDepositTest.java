/* 入金展示回归：真实记录含 LocalDate，空列表不能证明非空记录可转换；不调用模型或写入资产。 */
package com.itranswarp.exchange.service.assistant;

import static org.junit.jupiter.api.Assertions.*;
import com.itranswarp.exchange.repository.DepositRepository.Deposit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class AssistantDepositTest {
  /** 使用与数据库行相同的类型，覆盖可空事件编号和日期，而不是用简化 Map 绕过序列化。 */
  private Deposit deposit(String status,String amount){return new Deposit(UUID.randomUUID().toString(),42,new BigDecimal(amount),LocalDate.of(2026,11,2),status,status.equals("REJECTED")?"超出额度":"",status.equals("PENDING")?null:123L,1793577600000L,1793577600000L);}

  /** 多条申请展示准确金额、中文状态和数量；日期存在时也必须完成事实卡片转换。 */
  @Test void nonEmptyHistoryWithDatesRenders(){
    var presentation=new AssistantPresentation(AssistantTest.resolver());
    var view=presentation.render(AssistantRules.Intent.DEPOSIT,Map.of("records",List.of(deposit("SUCCEEDED","1000.10"),deposit("SUCCEEDED","2000.20"),deposit("PENDING","30.00"),deposit("REJECTED","40.00"))),AssistantTest.resolver().resolve("我的入金情况",null));
    assertTrue(view.highlights().get(0).contains("4 笔申请：2 笔已到账，1 笔待确认"));
    var rows=view.tables().get(0).rows();assertEquals(4,rows.size());assertEquals("1000.10",rows.get(0).get(0));assertEquals("已到账",rows.get(0).get(1));assertEquals("待确认",rows.get(2).get(1));assertEquals("超出额度",rows.get(3).get(3));assertEquals("1793577600000",rows.get(0).get(2));
  }

  /** 单笔查询也使用真实日期类型，并确保大额十进制不经过浮点数。 */
  @Test void detailWithDatePreservesExactAmount(){var view=new AssistantPresentation(AssistantTest.resolver()).render(AssistantRules.Intent.DEPOSIT,Map.of("record",deposit("SUCCEEDED","999999999999.99")),AssistantTest.resolver().resolve("入金状态",null));assertEquals("999999999999.99",view.tables().get(0).rows().get(0).get(0));}

  /** 没有申请仍明确说明为空，不能把演示配资虚构为入金。 */
  @Test void emptyHistoryRemainsExplicit(){var view=new AssistantPresentation(AssistantTest.resolver()).render(AssistantRules.Intent.DEPOSIT,Map.of("records",List.of()),AssistantTest.resolver().resolve("我的入金情况",null));assertTrue(view.highlights().get(0).contains("没有查到"));assertTrue(view.tables().get(0).rows().isEmpty());}
}
