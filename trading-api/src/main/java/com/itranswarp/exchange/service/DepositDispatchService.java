/* 入金发件箱：至少一次投递到 transfer，持久结果和引擎位置共同确认，不直接修改余额。 */
package com.itranswarp.exchange.service;

import com.itranswarp.exchange.repository.DepositRepository;
import com.itranswarp.exchange.securities.Currency;
import com.itranswarp.exchange.securities.SecuritiesChannels;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.output.OutputStore;
import com.itranswarp.exchange.util.JsonUtil;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.*;
import org.apache.kafka.clients.producer.*;

public final class DepositDispatchService implements AutoCloseable {
  private final DepositRepository repository;
  private final OutputStore outputs;
  private final LongSupplier applied;
  private final Consumer<Submission> send;
  private final Runnable release;
  private final ScheduledExecutorService executor;
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DepositDispatchService.class);

  /** 生产只持有一个生产者，命令固定投递内部 transfer Topic。 */
  public DepositDispatchService(DepositRepository repository,OutputStore outputs,
      TradingEngineApiProxyService engine,String bootstrap,String namespace) {
    this.repository=repository;this.outputs=outputs;
    applied=()->engine.internal("/status").path("applied").asLong(-1);
    var producer=new KafkaProducer<String,String>(Map.of("bootstrap.servers",bootstrap,"acks","all",
        "enable.idempotence",true,"max.block.ms",2000,"request.timeout.ms",2000,"delivery.timeout.ms",4000,
        "key.serializer","org.apache.kafka.common.serialization.StringSerializer","value.serializer","org.apache.kafka.common.serialization.StringSerializer"));
    var channels=new SecuritiesChannels(namespace);
    // 网络不确定性保留 PENDING，重试永远携带同一 uniqueId。
    send=request->{
      try { producer.send(new ProducerRecord<>(channels.topic("transfer"),JsonUtil.writeJson(request))).get(5,TimeUnit.SECONDS); }
      catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("入金投递中断",interrupted);}
      catch(Exception uncertain){throw new IllegalStateException("入金投递待确认",uncertain);}
    };
    release=()->producer.close(java.time.Duration.ofSeconds(5));
    executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"deposit-dispatch");t.setDaemon(true);return t;});
  }

  /** 测试注入投递和应用位置，模拟发送前后崩溃，不创建线程或网络资源。 */
  public DepositDispatchService(DepositRepository repository,OutputStore outputs,LongSupplier applied,Consumer<Submission> send) {
    this.repository=repository;this.outputs=outputs;this.applied=applied;this.send=send;release=()->{};executor=null;
  }

  /** Spring 初始化启动循环；关闭功能只禁止新申请，旧申请继续排空。 */
  public void start() {
    if(executor!=null) executor.scheduleWithFixedDelay(this::runSafely,0,1,TimeUnit.SECONDS);
  }

  /** 暂时故障不终止周期任务，不记录凭据；申请保留原标识等待下次处理。 */
  private void runSafely() {
    try { dispatch(System.currentTimeMillis()); }
    catch(RuntimeException unavailable){LOG.warn("模拟入金待重试：{}",unavailable.getClass().getSimpleName());}
  }

  /** 结果已持久但尚未安装时只等待，不能提前报告到账。 */
  public void dispatch(long now) {
    var due=repository.due(now);
    if(due.isEmpty()) return;
    long position=applied.getAsLong();
    if(position<0) return;
    for(var deposit:due) {
      if(!repository.claim(deposit.depositId(),now,now+5000)) continue;
      String unique="deposit:"+deposit.depositId();
      var result=outputs.result(deposit.userId(),unique);
      if(result.isPresent()) {
        var value=result.get();
        if(value.sequenceId()<=position) repository.finish(deposit,value.accepted(),value.reason(),value.sequenceId(),now);
      } else send.accept(new Submission(2,1,unique,deposit.userId(),new CreditCash(Currency.USD,deposit.amount())));
    }
  }

  /** 先停调度再关闭生产者，未确认申请在下次启动从持久发件箱接续。 */
  @Override public void close() {
    if(executor!=null) {
      executor.shutdownNow();
      try { executor.awaitTermination(6,TimeUnit.SECONDS); }
      catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
    release.run();
  }
}
