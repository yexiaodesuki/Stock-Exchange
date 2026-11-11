/* 行情助手只读仓储：同一可重复读事务读取投影进度和有界公开成交，不暴露订单及交易账户。 */
package com.itranswarp.exchange.repository;

import java.math.BigDecimal;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AssistantMarketRepository {
  public record Trade(long sequence,int index,long time,BigDecimal price,BigDecimal quantity){}
  public record Market(long security,List<Trade> trades,Trade last){}
  public record Snapshot(long projectionSequence,List<Market> markets){}
  private final JdbcTemplate jdbc;
  private final TransactionTemplate read;
  private final int maxTrades;
  /** 独立查询模板设一秒语句超时、五秒事务预算；有界行数不是完整市场统计的静默截断。 */
  public AssistantMarketRepository(DataSource source,int maxTrades){
    if(maxTrades<1||maxTrades>10000)throw new IllegalArgumentException("行情成交上限需在1至10000之间");
    this.maxTrades=maxTrades;jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(1);jdbc.setMaxRows(maxTrades+1);
    read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);read.setTimeout(5);
  }
  /** 捕获时点之后新成交属于下一次查询；投影落后或范围过大明确不可用，不冒充零成交。 */
  public Snapshot read(List<Long> securities,long from,long to){
    if(securities.isEmpty()||securities.size()>5||from<0||to<=from||to-from>7*86400000L)throw new IllegalArgumentException("行情查询范围无效");
    return read.execute(tx->{
      long projected=jdbc.queryForObject("SELECT sequenceId FROM projection_offsets WHERE name='quotation'",Long.class);
      long output=jdbc.queryForObject("SELECT sequenceId FROM engine_output_checkpoint WHERE id=1",Long.class);
      if(projected<output)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"行情追赶中，请稍后重试");
      var markets=new ArrayList<Market>();
      for(long security:securities){
        var trades=query("SELECT sequenceId,tradeIndex,createdAt,price,quantity FROM ticks WHERE securityId=? AND createdAt>=? AND createdAt<? ORDER BY createdAt,id LIMIT ?",security,from,to,maxTrades+1);
        if(trades.size()>maxTrades)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"所选范围成交过多，请缩短时间范围");
        var ordered=new ArrayList<>(trades);ordered.sort(Comparator.comparingLong(Trade::sequence).thenComparingInt(Trade::index));
        var latest=query("SELECT sequenceId,tradeIndex,createdAt,price,quantity FROM ticks WHERE securityId=? AND createdAt<? ORDER BY sequenceId DESC,tradeIndex DESC LIMIT 1",security,to);
        markets.add(new Market(security,List.copyOf(ordered),latest.isEmpty()?null:latest.get(0)));
      }
      return new Snapshot(projected,List.copyOf(markets));
    });
  }
  /** 白名单列转为精确十进制；所有用户输入只作为绑定参数，不拼接 SQL。 */
  private List<Trade> query(String sql,Object...args){return jdbc.query(sql,(rs,row)->new Trade(rs.getLong("sequenceId"),rs.getInt("tradeIndex"),rs.getLong("createdAt"),rs.getBigDecimal("price"),rs.getBigDecimal("quantity")),args);}
}
