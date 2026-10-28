/* 已提交输出发送器：Kafka 至少一次，Redis 实时提示可丢失；历史私人提示不在重启时轰炸用户。 */
package com.itranswarp.exchange.securities.output;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.kafka.clients.producer.*;
import com.itranswarp.exchange.securities.SecuritiesChannels;

public final class OutboxPublisher implements AutoCloseable {
    private final JdbcTemplate jdbc; private final MarketCache cache; private final KafkaProducer<String,String> kafka;
    private final SecuritiesChannels channels; private final LongSupplier applied; private final long boot=System.currentTimeMillis();
    private ScheduledExecutorService worker;
    private Runnable refresh=()->{};
    private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    /** 定期重建当前盘口，Redis 重启且没有新订单时也不能永久丢失盘口。 */
    public void refreshBooks(Runnable refresh){this.refresh=refresh;}
    /** 数据库提交后的记录才可发送；应用位置进一步限制未安装候选不能提前被推送。 */
    public OutboxPublisher(DataSource source,MarketCache cache,String bootstrap,String namespace,LongSupplier applied){
        jdbc=new JdbcTemplate(source);this.cache=cache;this.channels=new SecuritiesChannels(namespace);this.applied=applied;
        kafka=new KafkaProducer<>(Map.of("bootstrap.servers",bootstrap,"acks","all","enable.idempotence",true,"max.block.ms",2000,"request.timeout.ms",2000,"delivery.timeout.ms",4000,
                "key.serializer","org.apache.kafka.common.serialization.StringSerializer","value.serializer","org.apache.kafka.common.serialization.StringSerializer"));
    }
    /** 单工作线程发送，失败保留待发状态，下轮重试。 */
    public void start(){worker=Executors.newSingleThreadScheduledExecutor(task->{var t=new Thread(task,"securities-outbox");t.setDaemon(true);return t;});worker.scheduleWithFixedDelay(this::poll,100,200,TimeUnit.MILLISECONDS);}
    /** 发送成功和标记之间崩溃可重复，下游使用 eventId/sequence 去重。 */
    public void poll(){try{
        if(applied.getAsLong()<0)return;
        refresh.run();
        var rows=jdbc.queryForList("SELECT * FROM event_outbox WHERE sentAt IS NULL AND sequenceId<=? ORDER BY sequenceId,eventId LIMIT 100",applied.getAsLong());
        for(var row:rows){String type=(String)row.get("destination"),routing=(String)row.get("routing"),payload=(String)row.get("payload");
            if(type.equals("TICK"))kafka.send(new ProducerRecord<>(channels.topic("tick"),payload)).get(5,TimeUnit.SECONDS);
            else if(((Number)row.get("createdAt")).longValue()>=boot){
                if(type.equals("MARKET")){if(cache.put(Long.parseLong(routing),"book",((Number)row.get("sequenceId")).longValue(),json.readTree(payload).get("data")))cache.publish("market:"+routing,payload);}
                else cache.publish("user:"+routing,payload);
            }
            jdbc.update("UPDATE event_outbox SET sentAt=? WHERE eventId=? AND sentAt IS NULL",System.currentTimeMillis(),row.get("eventId"));
        }
    }catch(Exception failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("输出发送暂不可用，保留待发记录重试：{}",failure.getClass().getSimpleName());}}
    /** 关闭本发送器，不清空待发消息。 */
    @Override public void close(){if(worker!=null)worker.shutdownNow();kafka.close(java.time.Duration.ofSeconds(2));}
}
