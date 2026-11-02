/* 助手只读事实：同一快照的本人资产和活动单，以及独立时点的本人历史与入金投影。 */
package com.itranswarp.exchange.service.assistant;

import com.itranswarp.exchange.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

public final class AssistantReadService {
  private final TradingEngineApiProxyService engine;
  private final DepositService deposits;
  private final com.itranswarp.exchange.repository.AssistantReadRepository archive;
  /** 身份始终由控制器注入，服务中不接受模型指定用户。 */
  public AssistantReadService(TradingEngineApiProxyService engine,DepositService deposits,com.itranswarp.exchange.repository.AssistantReadRepository archive){this.engine=engine;this.deposits=deposits;this.archive=archive;}
  /** 单次引擎快照提取本人信息，金额以字符串输出；历史和入金单独标明来源。 */
  public Map<String,Object> read(long user,AssistantRules.Intent intent,Long security,Long order,String deposit){
    if(intent==AssistantRules.Intent.DEPOSIT){if(deposit==null||deposit.isBlank())return Map.of("source","MySQL 入金申请（最近20条）","records",deposits.list(user,0,20));return Map.of("source","MySQL 本人入金申请","record",deposits.get(user,deposit));}
    var context=exact(engine.internal("/accounts/"+user+"/assistant-context"));
    if(!context.path("cash").isArray()||!context.path("positions").isArray()||!context.path("orders").isArray()||context.path("sequenceId").asText().isBlank())throw new IllegalStateException("账户视图不完整");
    var facts=new LinkedHashMap<String,Object>();facts.put("source","引擎同一只读快照");facts.put("sequenceId",context.path("sequenceId").asText());
    facts.put("cash",context.path("cash"));facts.put("positions",context.path("positions"));
    var allOrders=context.path("orders");
    if(allOrders.size()>200)throw new IllegalArgumentException("活动委托超过助手上限，请使用原订单页面");
    var orders=new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();for(var row:allOrders)if(security==null||row.path("securityId").asLong()==security)orders.add(row);
    facts.put("activeOrders",orders);
    if(intent==AssistantRules.Intent.FREEZE){facts.put("calculatedBuyFrozen",buyFrozen(allOrders));facts.put("selectedBuyFrozen",buyFrozen(orders));}
    if(intent==AssistantRules.Intent.ORDER && order!=null){
      if(security==null)throw new IllegalArgumentException("查询指定订单需同时选择证券");
      // matches 方法先校验归属，即使订单属于别人也不会返回对手信息。
      facts.put("historySource","MySQL 本人订单及成交明细（独立查询时点，未必与内存快照同步）");facts.put("history",archive.order(user,security,order));
    }
    if(intent==AssistantRules.Intent.ORDER&&order==null)facts.put("finished",archive.finished(user,security));
    return facts;
  }
  /** 全部与所选证券冻结分开计算，不能把其他证券冻结算到当前证券。 */
  private String buyFrozen(JsonNode orders){BigDecimal amount=BigDecimal.ZERO;for(var row:orders)if("BUY".equals(row.path("direction").asText()))amount=amount.add(new BigDecimal(row.path("price").asText()).multiply(new BigDecimal(row.path("remaining").asText())));return amount.toPlainString();}
  /** 将所有数值叶节点转换为原始十进制文本，浏览器不可用 Number 舍入财务事实。 */
  private JsonNode exact(JsonNode node){
    if(node.isNumber())return com.fasterxml.jackson.databind.node.TextNode.valueOf(node.asText());
    if(node.isObject()){var out=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();node.fields().forEachRemaining(e->out.set(e.getKey(),exact(e.getValue())));return out;}
    if(node.isArray()){var out=new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();node.forEach(v->out.add(exact(v)));return out;}
    return node;
  }
}
