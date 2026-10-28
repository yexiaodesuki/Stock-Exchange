/* MySQL 行情投影：成交、四周期 K线和消费位置同事务提交，Redis 不作为权威历史。 */
package com.itranswarp.exchange.securities.output;

import java.math.BigDecimal;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.enums.Direction;

public final class MarketStore {
    public static final Map<String,Long> PERIODS=Map.of("sec",1000L,"min",60000L,"hour",3600000L,"day",86400000L);
    private final JdbcTemplate jdbc; private final TransactionTemplate transaction; private final SecurityCatalog catalog;
    /** 数据源只指向新证券库，构造不清理任何行情。 */
    public MarketStore(DataSource source,SecurityCatalog catalog){jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));this.catalog=catalog;}
    /** 返回整体投影完成位置，不将某只无成交证券的小序号当成服务落后。 */
    public long position(){return jdbc.queryForObject("SELECT sequenceId FROM projection_offsets WHERE name='quotation'",Long.class);}
    /** 按完整结果提交，重复结果不再增加成交量，缺口明确拒绝。 */
    public void apply(ExecutionEnvelope result){transaction.executeWithoutResult(status->{
        long previous=jdbc.queryForObject("SELECT sequenceId FROM projection_offsets WHERE name='quotation' FOR UPDATE",Long.class);
        if(result.sequenceId()<=previous)return;
        if(result.previousId()!=previous)throw new IllegalStateException("行情投影断链");
        int index=0;
        for(var trade:result.trades()){
            catalog.require(trade.securityId());if(trade.sequenceId()!=result.sequenceId() || trade.tradeIndex()!=index++)throw new IllegalStateException("成交顺序不一致");
            jdbc.update("INSERT INTO ticks(securityId,createdAt,makerOrderId,takerOrderId,price,quantity,sequenceId,takerDirection,tradeIndex) VALUES (?,?,?,?,?,?,?,?,?)",trade.securityId(),trade.createdAt(),trade.makerOrderId(),trade.takerOrderId(),trade.price(),trade.quantity(),trade.sequenceId(),trade.takerDirection()==Direction.BUY,trade.tradeIndex());
            for(var period:PERIODS.entrySet()){
                long time=Math.floorDiv(trade.createdAt(),period.getValue())*period.getValue();String table=period.getKey()+"_bars";
                jdbc.update("INSERT INTO "+table+"(securityId,startTime,openPrice,highPrice,lowPrice,closePrice,quantity) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE highPrice=GREATEST(highPrice,VALUES(highPrice)),lowPrice=LEAST(lowPrice,VALUES(lowPrice)),closePrice=VALUES(closePrice),quantity=quantity+VALUES(quantity)",trade.securityId(),time,trade.price(),trade.price(),trade.price(),trade.price(),trade.quantity());
            }
        }
        jdbc.update("UPDATE projection_offsets SET sequenceId=? WHERE name='quotation'",result.sequenceId());
    });}
    /** 公开成交不包含交易账户或私人成交对手信息，按稳定事件顺序返回最近记录。 */
    public List<Tick> ticks(long security,int limit){catalog.require(security);limit(limit);return jdbc.query("SELECT * FROM ticks WHERE securityId=? ORDER BY sequenceId DESC,tradeIndex DESC LIMIT ?",(rs,row)->new Tick(rs.getLong("securityId"),rs.getLong("sequenceId"),rs.getInt("tradeIndex"),rs.getLong("createdAt"),rs.getBigDecimal("price"),rs.getBigDecimal("quantity"),rs.getBoolean("takerDirection")?"BUY":"SELL"),security,limit);}
    /** 使用白名单周期和固定参数查询，不能把用户提供的表名拼入 SQL。 */
    public List<Bar> bars(long security,String period,long from,long to,int limit){catalog.require(security);limit(limit);if(!PERIODS.containsKey(period)||from<0||to<from)throw new IllegalArgumentException("K线范围无效");
        var values=jdbc.query("SELECT * FROM "+period+"_bars WHERE securityId=? AND startTime>=? AND startTime<=? ORDER BY startTime DESC LIMIT ?",(rs,row)->new Bar(rs.getLong("securityId"),rs.getLong("startTime"),rs.getBigDecimal("openPrice"),rs.getBigDecimal("highPrice"),rs.getBigDecimal("lowPrice"),rs.getBigDecimal("closePrice"),rs.getBigDecimal("quantity")),security,from,to,limit);
        var sorted=new ArrayList<>(values);Collections.reverse(sorted);return List.copyOf(sorted);
    }
    /** 限制单次数据量，避免无限历史查询拖垮服务。 */
    public static void limit(int value){if(value<1||value>1000)throw new IllegalArgumentException("数量范围为 1 至 1000");}
    /** 同一数据库快照读取进度和内容，不能给旧数据标上更晚的投影位置。 */
    public <T> View<T> view(java.util.function.Supplier<T> read){return transaction.execute(status->new View<>(position(),read.get()));}
    public record View<T>(long sequenceId,T data){}
    public record Tick(long securityId,long sequenceId,int tradeIndex,long createdAt,BigDecimal price,BigDecimal quantity,String direction){}
    public record Bar(long securityId,long startTime,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,BigDecimal quantity){}
}
