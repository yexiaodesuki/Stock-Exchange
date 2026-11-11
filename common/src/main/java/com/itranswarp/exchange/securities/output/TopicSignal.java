/* Kafka 行情提示只触发数据库追赶，不把 offset 或不可信消息体当成行情权威。 */
package com.itranswarp.exchange.securities.output;

import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import com.itranswarp.exchange.securities.SecuritiesChannels;

public final class TopicSignal implements AutoCloseable {
    private final String bootstrap;private final SecuritiesChannels channels;private final Runnable poll;private final BooleanSupplier ready;
    private volatile boolean running;private volatile KafkaConsumer<String,String> consumer;private Thread thread;
    /** 构造阶段不建立 Kafka 连接。 */
    public TopicSignal(String bootstrap,String namespace,Runnable poll,BooleanSupplier ready){this.bootstrap=bootstrap;channels=new SecuritiesChannels(namespace);this.poll=poll;this.ready=ready;}
    /** 单消费者唤醒同一个串行投影入口。 */
    public void start(){running=true;thread=new Thread(this::run,"securities-quotation-hints");thread.setDaemon(true);thread.start();}
    /** 结果由 MySQL 校验，Kafka 不可用时定时追赶仍然继续。 */
    private void run(){try(var kafka=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",bootstrap,"group.id",channels.group("quotation"),"enable.auto.commit",false,"auto.offset.reset","earliest","session.timeout.ms",10000,"heartbeat.interval.ms",2000,"default.api.timeout.ms",3000,"key.deserializer","org.apache.kafka.common.serialization.StringDeserializer","value.deserializer","org.apache.kafka.common.serialization.StringDeserializer"))){
        consumer=kafka;kafka.subscribe(List.of(channels.topic("tick")));while(running){var records=kafka.poll(Duration.ofMillis(250));if(!records.isEmpty()){poll.run();if(ready.getAsBoolean())kafka.commitSync();}}
    }catch(org.apache.kafka.common.errors.WakeupException stopped){/* 正常停止。 */}catch(Exception unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("行情 Kafka 提示不可用，保留数据库轮询");}finally{consumer=null;running=false;}}
    /** 关闭本实例消费者，不清理其他消费组。 */
    @Override public void close(){running=false;var current=consumer;if(current!=null)current.wakeup();if(thread!=null)try{thread.join(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}
