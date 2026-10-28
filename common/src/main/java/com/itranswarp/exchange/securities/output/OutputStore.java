/* 执行结果、订单历史与待发消息同事务保存；重放相同输出只校验，不重复记账或通知。 */
package com.itranswarp.exchange.securities.output;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.itranswarp.exchange.util.JsonUtil;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;

public final class OutputStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    /** 缺少 V2 表直接失败，不退回易丢失的纯内存输出。 */
    public OutputStore(DataSource source){jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));position();}
    /** 持久位置不等于内存执行位置，启动选择快照时必须取两者安全边界。 */
    public long position(){return jdbc.queryForObject("SELECT sequenceId FROM engine_output_checkpoint WHERE id=1",Long.class);}

    /** 在一笔事务提交确定结果、历史投影、发送清单和连续位置。 */
    public void save(SequencedEvent event,ExecutionEnvelope result,boolean historical){
        String data=JsonUtil.writeJson(result),checksum=hash(data);
        transaction.executeWithoutResult(status->{
            // 固定行锁使输出位置不能跨过未提交事件。
            long previous=jdbc.queryForObject("SELECT sequenceId FROM engine_output_checkpoint WHERE id=1 FOR UPDATE",Long.class);
            var existing=jdbc.queryForList("SELECT resultData,checksum FROM engine_results WHERE sequenceId=?",result.sequenceId());
            if(!existing.isEmpty()){
                var old=existing.get(0);String raw=(String)old.get("resultData");
                if(!hash(raw).equals(old.get("checksum")) || !checksum.equals(old.get("checksum")))throw new IllegalStateException("同事件位置的持久输出不一致");
                return;
            }
            if(previous!=result.previousId())throw new IllegalStateException("执行输出位置断链");
            jdbc.update("INSERT INTO engine_results VALUES (?,?,?,?,?,?,?)",result.sequenceId(),result.previousId(),result.userId(),result.uniqueId(),result.createdAt(),data,checksum);
            for(var order:result.orders()) saveOrder(order);
            for(var trade:result.trades()) saveTrade(trade,result.orders());
            if(result.accepted() && event.command() instanceof CreditCash cash)jdbc.update("INSERT INTO transfer_logs VALUES (?,?, 'CASH','USD',NULL,?,?,'SUCCESS','DEPOSIT',?)",event.uniqueId(),cash.amount(),event.sequenceId(),event.createdAt(),event.userId());
            if(result.accepted() && event.command() instanceof CreditPosition shares)jdbc.update("INSERT INTO transfer_logs VALUES (?,?,'POSITION',NULL,?, ?,?,'SUCCESS','DEPOSIT',?)",event.uniqueId(),shares.quantity(),shares.securityId(),event.sequenceId(),event.createdAt(),event.userId());
            if(!result.trades().isEmpty())outbox(result,"TICK","tick",data);
            if(!historical){
                for(var book:result.books())outbox(result,"MARKET",Long.toString(book.securityId()),JsonUtil.writeJson(Map.of("type","orderbook","eventId",result.sequenceId()+":book:"+book.securityId(),"securityId",Long.toString(book.securityId()),"sequenceId",Long.toString(result.sequenceId()),"data",book)));
                var users=new TreeSet<Long>();users.add(result.userId());result.orders().forEach(o->users.add(o.userId()));
                for(long user:users){
                    var message=new LinkedHashMap<String,Object>();message.put("type","account.changed");message.put("eventId",result.sequenceId()+":user:"+user);message.put("sequenceId",Long.toString(result.sequenceId()));message.put("accepted",result.accepted());message.put("reason",result.reason());
                    if(event.command() instanceof PlaceOrder order)message.put("securityId",Long.toString(order.securityId()));
                    if(event.command() instanceof CancelOrder cancel)message.put("securityId",Long.toString(cancel.securityId()));
                    if(result.orderId()!=null)message.put("orderId",Long.toString(result.orderId()));
                    outbox(result,"PRIVATE",Long.toString(user),JsonUtil.writeJson(message));
                }
            }
            jdbc.update("UPDATE engine_output_checkpoint SET sequenceId=? WHERE id=1",result.sequenceId());
        });
    }
    /** 保留活动和最终订单；按事件有序更新，因此撤单与完全成交不会丢失历史。 */
    private void saveOrder(ExecutionEnvelope.OrderChange o){
        jdbc.update("INSERT INTO orders(id,securityId,userId,sequenceId,createdAt,updatedAt,direction,price,quantity,unfilledQuantity,status) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE updatedAt=VALUES(updatedAt),unfilledQuantity=VALUES(unfilledQuantity),status=VALUES(status)",
                o.id(),o.securityId(),o.userId(),o.sequenceId(),o.createdAt(),o.updatedAt(),o.direction().name(),o.price(),o.quantity(),o.remaining(),o.status());
    }
    /** 每笔成交记录买卖双方明细和当次清算后的剩余数量，不混用最终订单剩余量。 */
    private void saveTrade(ExecutionEnvelope.Trade trade,List<ExecutionEnvelope.OrderChange> orders){
        for(boolean maker:new boolean[]{true,false}){
            long id=maker?trade.makerOrderId():trade.takerOrderId(),counter=maker?trade.takerOrderId():trade.makerOrderId();
            Direction direction=maker?trade.takerDirection().negate():trade.takerDirection();
            long user=direction==Direction.BUY?trade.buyerId():trade.sellerId(),other=direction==Direction.BUY?trade.sellerId():trade.buyerId();
            var remaining=maker?trade.makerRemaining():trade.takerRemaining();String type=maker?"MAKER":"TAKER";
            jdbc.update("INSERT INTO match_details(securityId,counterOrderId,counterUserId,createdAt,direction,orderId,price,quantity,sequenceId,type,userId) VALUES (?,?,?,?,?,?,?,?,?,?,?)",trade.securityId(),counter,other,trade.createdAt(),direction.name(),id,trade.price(),trade.quantity(),trade.sequenceId(),type,user);
            jdbc.update("INSERT INTO clearings(securityId,counterOrderId,counterUserId,createdAt,direction,matchPrice,matchQuantity,orderId,orderStatusAfterClearing,orderUnfilledQuantityAfterClearing,sequenceId,type,userId) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",trade.securityId(),counter,other,trade.createdAt(),direction.name(),trade.price(),trade.quantity(),id,remaining.signum()==0?"FULLY_FILLED":"PARTIAL_FILLED",remaining,trade.sequenceId(),type,user);
        }
    }
    /** 稳定事件标识让发送后崩溃的重试仍可由下游去重。 */
    private void outbox(ExecutionEnvelope result,String destination,String routing,String payload){jdbc.update("INSERT INTO event_outbox(eventId,sequenceId,destination,routing,payload,createdAt) VALUES (?,?,?,?,?,?)",result.sequenceId()+":"+destination+":"+routing,result.sequenceId(),destination,routing,payload,result.createdAt());}

    /** 全局输出按序分页，零成交/业务拒绝事件也参与进度，避免等待不存在的 Tick。 */
    public List<ExecutionEnvelope> page(long after,long upper,int limit){
        if(after<0 || upper<after || limit<1 || limit>1000)throw new IllegalArgumentException("输出分页无效");
        return jdbc.query("SELECT resultData,checksum FROM engine_results WHERE sequenceId>? AND sequenceId<=? ORDER BY sequenceId LIMIT ?",(rs,row)->{
            String data=rs.getString(1);if(!hash(data).equals(rs.getString(2)))throw new IllegalStateException("持久输出校验和错误");return JsonUtil.readJson(data,ExecutionEnvelope.class);
        },after,upper,limit);
    }
    /** 返回本人请求的持久结果；不会根据请求标识泄露别人的交易。 */
    public Optional<ExecutionEnvelope> result(long user,String unique){return jdbc.query("SELECT resultData,checksum FROM engine_results WHERE userId=? AND uniqueId=?",(rs,row)->{String data=rs.getString(1);if(!hash(data).equals(rs.getString(2)))throw new IllegalStateException("请求结果校验失败");return JsonUtil.readJson(data,ExecutionEnvelope.class);},user,unique).stream().findFirst();}
    /** 使用原始 UTF-8 文本生成 SHA-256，数据库和恢复共享同一校验方法。 */
    public static String hash(String data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
