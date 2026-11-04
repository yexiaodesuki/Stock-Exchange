/* 公开成交摘要：金额由本系统成交精确计算，不调用引擎资产、不生成价格预测或补造K线。 */
package com.itranswarp.exchange.service.assistant;

import com.itranswarp.exchange.repository.AssistantMarketRepository;
import com.itranswarp.exchange.securities.SecurityCatalog;
import java.math.*;
import java.util.*;

public final class AssistantMarketService {
  private final AssistantMarketRepository repository;
  private final SecurityCatalog catalog;
  /** 只接收公开行情仓储和权威证券目录。 */
  public AssistantMarketService(AssistantMarketRepository repository,SecurityCatalog catalog){this.repository=repository;this.catalog=catalog;}
  /** 单证券或明确全部证券共享同一数据库快照，模型不能指定额外证券。 */
  public Map<String,Object> read(Long security,AssistantTimeRangeResolver.Range range){
    List<Long> ids=security==null?catalog.all().stream().map(s->s.securityId()).toList():List.of(catalog.require(security).securityId());
    var snapshot=repository.read(ids,range.from(),range.to());
    var values=snapshot.markets().stream().map(this::summarize).toList();
    return Map.of("source","MySQL 本系统模拟成交（同一只读快照）","projectionSequence",Long.toString(snapshot.projectionSequence()),"from",Long.toString(range.from()),"to",Long.toString(range.to()),"range",range.label(),"markets",values);
  }
  /** 首末按事件次序，区间变化不是前收盘日涨跌幅；空范围的最后一笔单独标注。 */
  Map<String,Object> summarize(AssistantMarketRepository.Market market){
    var rows=market.trades();var out=new LinkedHashMap<String,Object>();out.put("name",catalog.require(market.security()).name());out.put("symbol",catalog.require(market.security()).symbol());out.put("count",Integer.toString(rows.size()));
    if(market.last()!=null){out.put("lastTime",Long.toString(market.last().time()));out.put("lastPrice",money(market.last().price()));}
    if(rows.isEmpty()){out.put("state","NO_TRADES");return Map.copyOf(out);}
    BigDecimal volume=BigDecimal.ZERO,amount=BigDecimal.ZERO,high=rows.get(0).price(),low=high;
    for(var row:rows){volume=volume.add(row.quantity());amount=amount.add(row.price().multiply(row.quantity()));high=high.max(row.price());low=low.min(row.price());}
    BigDecimal first=rows.get(0).price(),last=rows.get(rows.size()-1).price();out.put("state",rows.size()==1?"ONE_TRADE":"READY");out.put("volume",volume.stripTrailingZeros().toPlainString());out.put("amount",money(amount));out.put("first",money(first));out.put("last",money(last));out.put("high",money(high));out.put("low",money(low));
    if(rows.size()>1)out.put("changePercent",last.subtract(first).multiply(new BigDecimal("100")).divide(first,2,RoundingMode.HALF_UP).toPlainString());
    return Map.copyOf(out);
  }
  /** 金额不允许隐式舍入，只有展示用百分比另外明确保留两位。 */
  private String money(BigDecimal value){return value.setScale(2,RoundingMode.UNNECESSARY).toPlainString();}
}
