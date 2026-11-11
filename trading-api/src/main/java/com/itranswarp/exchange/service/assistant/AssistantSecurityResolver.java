/* 证券名称解析：使用目录全名、代码和短名；行情全部范围必须明确表达，未知目标不猜编号。 */
package com.itranswarp.exchange.service.assistant;
import com.itranswarp.exchange.securities.*;
import java.util.*;
import java.util.regex.Pattern;

public final class AssistantSecurityResolver {
  private final SecurityCatalog catalog;
  public record Selection(Long securityId,String label,String clarification){}
  /** 标识来源只有服务器证券目录，不接受模型生成的编号。 */
  public AssistantSecurityResolver(SecurityCatalog catalog){this.catalog=catalog;}
  /** 中文全名/前两字和英文代码均可定位，多个命中要求澄清。 */
  public Selection resolve(String question,Long explicit){
    if(question.matches("(?s).*(除了|以外|不要看|不看|排除).*"))return new Selection(null,"","暂不支持排除证券的组合筛选，请直接说一只证券名称，或问“我的所有订单”。");
    var hits=new LinkedHashMap<Long,String>();
    for(var s:catalog.all()){
      boolean code=Pattern.compile("(?i)(?<![a-z0-9_])"+Pattern.quote(s.symbol())+"(?![a-z0-9_])").matcher(question).find();
      String shortName=s.name().substring(0,Math.min(2,s.name().length()));
      if(code||question.contains(s.name())||question.contains(shortName))hits.put(s.securityId(),s.name());
    }
    if(hits.size()>1)return new Selection(null,"","你提到了多只证券，请一次选择一只，或改问“我的所有订单”。");
    if(!hits.isEmpty()){
      long id=hits.keySet().iterator().next();
      if(explicit!=null&&explicit!=id)return new Selection(null,"","所选证券与问题名称不一致，请重新选择。");
      return new Selection(id,hits.get(id),"");
    }
    if(explicit!=null){var s=catalog.require(explicit);return new Selection(explicit,s.name(),"");}
    String rest=question.replaceAll("我的|本人|所有|全部|最近|最新|最后|历史|当前|现在|已经|已完成|未完成|今天|昨天|本周|上周|本月|上月|这个|哪些|什么|查看|查询|看看|一下|订单|委托|挂单|成交|交易|记录|账户|余额|持仓|股票|证券|状态|怎么样|情况|入金|为什么|多少钱|资金|冻结|可用|可以用|还有|有没有|没有|有多少|显示|撤销|取消|完成|笔|已|未|请|帮我|我|的|在|是|吗|呢|[\\p{Punct}\\s，。？！]","");
    rest=rest.replaceAll("多少|几|有|一|最后|最新|最近|那个|这笔|那笔|这个|那个","");
    if(question.matches("(?s).*(订单|委托|挂单|持仓).*")&&!rest.isBlank())return new Selection(null,"","未能确定证券名称。可使用星河科技（星河/STAR）、青山能源（青山/GREEN）、安康医药（安康/CARE）、优选消费（优选/DAILY）或远航工业（远航/SAIL），也可以问“我的所有订单”。");
    return new Selection(null,"全部证券","");
  }
  /** 友好名称取自目录，不让内部编号取代证券名称。 */
  public String name(long id){return catalog.require(id).name();}
  /** 行情没有指定目标时不可默认第一只；未知名称或部分组合不能被当成五证券汇总。 */
  public Selection resolveMarket(String question,Long explicit){
    boolean all=question.matches("(?s).*(五只|五支|全部|所有|整体).*");
    var selection=resolve(question,explicit);
    if(!selection.clarification().isBlank())return selection;
    if(question.matches("(?s).*(和|与|及|、).*")&&selection.securityId()!=null)return new Selection(null,"","请一次查询一只证券，或明确查询五只证券整体行情；组合问题暂不支持。");
    if(selection.securityId()!=null)return all?new Selection(null,"","请问一只证券，或明确问五只证券的整体行情，不混合两个范围。"):selection;
    if(all){
      String rest=question.replaceAll("五只|五支|全部|所有|整体|股票|证券|行情|走势|成交量|成交金额|最高价|最低价|成交|摘要|情况|如何|怎么样|最近|过去|今天|昨天|本周|上周|本月|上月|近|小时|天|的|请|查看|看看|一下|目前|现在|[一二三四五六七八九十百0-9\\s\\p{Punct}，。？！]","");
      if(rest.isBlank())return new Selection(null,"五证券","");
    }
    return new Selection(null,"","请说证券名称，例如“星河最近行情”，或问“五只股票最近24小时行情”；未知证券和多只组合暂不支持。");
  }
}
