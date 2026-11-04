/* 确定性结论与事实卡片：支持入金日期、行情统计和知识出处，不展示模型编造的业务事实。 */
package com.itranswarp.exchange.service.assistant;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;

public final class AssistantPresentation {
  private final AssistantSecurityResolver securities;
  // 入金记录包含 LocalDate；独立转换器不能依赖 Spring MVC 的日期模块自动配置。
  private final ObjectMapper json=new ObjectMapper()
      .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  public record Table(String title,List<String> columns,List<List<String>> rows){}
  public record Result(String explanation,List<String> highlights,List<Table> tables){}
  /** 名称唯一来源是服务端证券目录。 */
  public AssistantPresentation(AssistantSecurityResolver securities){this.securities=securities;}
  /** 金额精确补两位，股数去无意义尾零；非法精度不能默默四舍五入。 */
  private String number(JsonNode value,boolean money){var amount=new BigDecimal(value.asText("0"));return money?amount.setScale(2,java.math.RoundingMode.UNNECESSARY).toPlainString():amount.stripTrailingZeros().toPlainString();}
  /** 中文状态映射保留未知状态，不能默认说成已成交。 */
  private String status(String value){return switch(value){case "FULLY_FILLED"->"全部成交";case "FULLY_CANCELLED"->"全部撤销";case "PARTIAL_CANCELLED"->"部分成交后撤销";case "PENDING"->"待确认";case "SUCCEEDED"->"已到账";case "REJECTED"->"已拒绝";default->value;};}
  /** 活动订单剩余小于原数量时，才说明部分成交。 */
  private String activeStatus(JsonNode row){return new BigDecimal(row.path("remaining").asText()).compareTo(new BigDecimal(row.path("quantity").asText()))<0?"部分成交，仍在挂单":"待成交";}
  /** 行内显示证券名称，不显示对手身份，也不要求用户认识编号。 */
  private List<String> orderRow(JsonNode row,boolean active){return List.of(securities.name(row.path("securityId").asLong()),row.path("direction").asText().equals("BUY")?"买入":"卖出",number(row.path("price"),true),number(row.path("quantity"),false),number(row.path(active?"remaining":"unfilledQuantity"),false),active?activeStatus(row):status(row.path("status").asText()));}
  /** 直接回答金额、订单数量与入金状态，明确历史展示窗口和来源时点。 */
  public Result render(AssistantRules.Intent intent,Map<String,Object> facts,AssistantSecurityResolver.Selection selection){
    JsonNode data=json.valueToTree(facts);var highlights=new ArrayList<String>();var tables=new ArrayList<Table>();String scope=selection.label();
    if(intent==AssistantRules.Intent.MARKET)return market(data);
    if(intent==AssistantRules.Intent.KNOWLEDGE){
      if(!data.path("answer").asText().isBlank())highlights.add(data.path("answer").asText());
      else for(var row:data.path("sources"))highlights.add(row.path("excerpt").asText());
      if(highlights.isEmpty())highlights.add("项目知识库没有收录足够依据，暂时无法回答；不会使用模型常识猜测本项目规则。");
      return new Result(AssistantRules.explain(intent),highlights,List.of());
    }
    if(intent==AssistantRules.Intent.DEPOSIT){
      var rows=new ArrayList<List<String>>();var values=json.createArrayNode();if(data.has("record"))values.add(data.get("record"));else data.path("records").forEach(values::add);int pending=0,success=0;
      for(var row:values){String state=row.path("status").asText();if(state.equals("PENDING"))pending++;if(state.equals("SUCCEEDED"))success++;rows.add(List.of(number(row.path("amount"),true),status(state),row.path("createdAt").asText(),state.equals("REJECTED")?row.path("reason").asText():""));}
      highlights.add(rows.isEmpty()?"没有查到你的模拟入金申请；初始演示配资不属于这里的入金申请。":"本次展示 "+rows.size()+" 笔申请："+success+" 笔已到账，"+pending+" 笔待确认（最多最近20笔，不代表全部历史）。");
      if(pending>0)highlights.add("待确认不能证明具体故障或预计到账时间，请勿重复申请补发。");
      tables.add(new Table("模拟入金（USD）",List.of("金额","状态","申请时间","拒绝原因"),rows));
    }else{
      JsonNode cash=data.path("cash").path(0);String available=cash.isMissingNode()?"0.00":number(cash.path("available"),true),frozen=cash.isMissingNode()?"0.00":number(cash.path("frozen"),true);
      highlights.add("你的共享 USD 资金：可用 "+available+"，冻结 "+frozen+"。");
      var positions=new ArrayList<List<String>>();for(var row:data.path("positions"))if(selection.securityId()==null||row.path("securityId").asLong()==selection.securityId())positions.add(List.of(securities.name(row.path("securityId").asLong()),number(row.path("available"),false),number(row.path("frozen"),false)));
      if(intent==AssistantRules.Intent.ACCOUNT)tables.add(new Table(scope+"持仓",List.of("证券","可用股数","冻结股数"),positions));
      if(intent==AssistantRules.Intent.ACCOUNT&&positions.isEmpty())highlights.add(selection.securityId()==null?"你当前没有证券持仓。":"你当前没有"+scope+"持仓，可用与冻结均为0股。");
      var orders=new ArrayList<List<String>>();for(var row:data.path("activeOrders"))orders.add(orderRow(row,true));
      if(intent==AssistantRules.Intent.ORDER||intent==AssistantRules.Intent.FREEZE){highlights.add(scope+"当前有 "+orders.size()+" 笔活动委托。");tables.add(new Table(scope+"活动委托",List.of("证券","方向","限价 USD","原股数","剩余股数","状态"),orders));}
      if(intent==AssistantRules.Intent.FREEZE){String calculated=number(data.path("calculatedBuyFrozen"),true);boolean same=new BigDecimal(calculated).compareTo(new BigDecimal(frozen))==0;highlights.add("全部活动买单按限价×剩余股数计算占用 "+calculated+" USD，"+(same?"与账户冻结金额一致。":"与账户冻结金额不一致，请检查账户和订单页面，助手不修补或编造差额。"));if(selection.securityId()!=null)highlights.add(scope+"买单占用其中 "+number(data.path("selectedBuyFrozen"),true)+" USD；卖单冻结证券股数。");}
      if(data.has("finished")){var rows=new ArrayList<List<String>>();for(var row:data.path("finished").path("records"))rows.add(orderRow(row,false));highlights.add(scope+"已结束订单共 "+data.path("finished").path("total").asText()+" 笔，下方仅展示最近 "+rows.size()+" 笔（最多20笔）。");tables.add(new Table("最近已结束委托（MySQL 独立时点）",List.of("证券","方向","限价 USD","原股数","未成交股数","状态"),rows));}
      if(data.has("history")){var row=data.path("history").path("order");tables.add(new Table("所选历史委托",List.of("证券","方向","限价 USD","原股数","未成交股数","状态"),List.of(orderRow(row,false))));}
    }
    return new Result(AssistantRules.explain(intent),highlights,tables);
  }
  /** 公开行情不附带本人资金；零成交与单笔成交分别说明，旧价格单独标成范围外数据。 */
  private Result market(JsonNode data){
    var highlights=new ArrayList<String>();var tables=new ArrayList<Table>();
    String range=data.path("range").asText();highlights.add("统计范围："+range+"；UTC "+java.time.Instant.ofEpochMilli(data.path("from").asLong())+" 至 "+java.time.Instant.ofEpochMilli(data.path("to").asLong())+"（左闭右开）。");
    for(var row:data.path("markets")){
      String name=row.path("name").asText();
      if(row.path("state").asText().equals("NO_TRADES")){
        highlights.add(name+"在该范围内无成交，不能生成区间趋势。"+(row.has("lastTime")?"范围外最近成交："+java.time.Instant.ofEpochMilli(row.path("lastTime").asLong())+"，价格 "+row.path("lastPrice").asText()+" USD（不计入本次统计）。":"本系统尚无该证券的成交记录。"));continue;
      }
      highlights.add(name+"共 "+row.path("count").asText()+" 笔成交，"+row.path("volume").asText()+" 股，成交金额 "+row.path("amount").asText()+" USD。"+(row.path("state").asText().equals("ONE_TRADE")?"只有一笔成交，不足以判断趋势。":"区间首末价变化 "+row.path("changePercent").asText()+"%（不是日涨跌幅；展示百分比四舍五入至两位）。"));
      tables.add(new Table(name+"成交统计",List.of("首笔价格 USD","末笔价格 USD","最高价 USD","最低价 USD","最后成交时间"),List.of(List.of(row.path("first").asText(),row.path("last").asText(),row.path("high").asText(),row.path("low").asText(),row.path("lastTime").asText()))));
    }
    return new Result(AssistantRules.explain(AssistantRules.Intent.MARKET),highlights,tables);
  }
}
