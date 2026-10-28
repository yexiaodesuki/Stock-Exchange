/* 内部证券定序：先提交事件与幂等键，后确认消息；提示发送失败由引擎数据库追赶兜底。 */
package com.itranswarp.exchange.sequencer;

import com.itranswarp.exchange.securities.SecuritiesChannels;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.persistence.ProcessLease;
import com.itranswarp.exchange.util.JsonUtil;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

public final class SequenceService implements AutoCloseable {
  private final SequenceHandler handler;
  private final DataSource source;
  private final String bootstrap;
  private final SecuritiesChannels channels;
  private volatile boolean running, ready;
  private volatile KafkaConsumer<String, String> consumer;
  private Thread thread;
  private ProcessLease lease;
  private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(getClass());

  /** 不创建旧 Topic 或在构造阶段启动消费。 */
  public SequenceService(
      DataSource source, SecurityCatalog catalog, String bootstrap, String namespace) {
    this.source = source;
    this.handler = new SequenceHandler(source, catalog, namespace);
    this.bootstrap = bootstrap;
    this.channels = new SecuritiesChannels(namespace);
  }

  /** 专属会话锁拒绝第二定序实例。 */
  public synchronized void start() {
    if (thread != null) throw new IllegalStateException("证券定序器已启动");
    lease = new ProcessLease(source, "sequencer");
    running = true;
    thread = new Thread(this::run, "securities-sequencer");
    thread.setDaemon(true);
    thread.start();
  }

  /** 数据库失败不提交 offset；Kafka 提示失败不撤销已经提交的事件。 */
  private void run() {
    var config = new HashMap<String, Object>();
    config.put("bootstrap.servers", bootstrap);
    config.put("group.id", channels.group("sequencer"));
    config.put("enable.auto.commit", false);
    config.put("auto.offset.reset", "earliest");
    config.put("max.poll.records", 10);
    config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    config.put("default.api.timeout.ms", 3000);
    config.put("session.timeout.ms", 10000);
    config.put("heartbeat.interval.ms", 2000);
    try (var kafka = new KafkaConsumer<String, String>(config);
        var producer =
            new KafkaProducer<String, String>(
                Map.of(
                    "bootstrap.servers",
                    bootstrap,
                    "acks",
                    "all",
                    "enable.idempotence",
                    true,
                    "max.block.ms",
                    3000,
                    "request.timeout.ms",
                    3000,
                    "delivery.timeout.ms",
                    6000,
                    "key.serializer",
                    "org.apache.kafka.common.serialization.StringSerializer",
                    "value.serializer",
                    "org.apache.kafka.common.serialization.StringSerializer"))) {
      consumer = kafka;
      kafka.subscribe(List.of(channels.topic("sequence"), channels.topic("transfer")));
      while (running) {
        lease.check();
        var records = kafka.poll(Duration.ofMillis(250));
        ready = !kafka.assignment().isEmpty();
        for (var record : records) {
          lease.check();
          var event = process(record.topic(), record.value());
          try {
            producer
                .send(new ProducerRecord<>(channels.topic("trade"), JsonUtil.writeJson(event)))
                .get(8, TimeUnit.SECONDS);
          } catch (Exception failed) {
            logger.warn("事件 {} 已提交，Kafka 提示失败，交由数据库追赶", event.sequenceId());
          }
        }
        if (!records.isEmpty()) kafka.commitSync();
      }
    } catch (org.apache.kafka.common.errors.WakeupException stopped) {
      /* 本实例正常停止。 */
    } catch (Exception failure) {
      logger.error("证券定序失败，停止确认后续消息", failure);
    } finally {
      ready = false;
      running = false;
      consumer = null;
      if (lease != null) lease.close();
    }
  }

  /** 按 Topic 区分普通订单和资金配给；未来公开 API 不得直接暴露本方法。 */
  public SequencedEvent process(String topic, String json) {
    return handler.process(topic, json);
  }

  /** 必须实际分配消费分区才报告就绪。 */
  public boolean ready() {
    return ready && running;
  }

  /** 只关闭本实例线程和会话锁。 */
  @Override
  public synchronized void close() {
    running = false;
    ready = false;
    var current = consumer;
    if (current != null) current.wakeup();
    if (thread != null)
      try {
        thread.join(10000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    if (thread == null && lease != null) lease.close();
  }
}
