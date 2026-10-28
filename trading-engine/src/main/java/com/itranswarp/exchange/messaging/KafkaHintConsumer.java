/* Kafka 仅唤醒数据库追赶；通知丢失时定时轮询仍能发现已提交事件。 */
package com.itranswarp.exchange.messaging;

import com.itranswarp.exchange.TradingEngineService;
import com.itranswarp.exchange.securities.SecuritiesChannels;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.KafkaConsumer;

public final class KafkaHintConsumer implements AutoCloseable {
  private final TradingEngineService runtime;
  private final String bootstrap;
  private final SecuritiesChannels channels;
  private volatile boolean running;
  private volatile KafkaConsumer<String, String> consumer;
  private Thread thread;

  /** 构造时不开始消费。 */
  public KafkaHintConsumer(TradingEngineService runtime, String bootstrap, String namespace) {
    this.runtime = runtime;
    this.bootstrap = bootstrap;
    this.channels = new SecuritiesChannels(namespace);
  }

  /** 提示线程也只能通过同一个同步追赶入口修改状态。 */
  public synchronized void start() {
    running = true;
    thread = new Thread(this::run, "securities-kafka-hints");
    thread.setDaemon(true);
    thread.start();
  }

  /** 不使用 Kafka 消息体记账，只执行数据库中已提交的事件。 */
  private void run() {
    try (var kafka =
        new KafkaConsumer<String, String>(
            Map.of(
                "bootstrap.servers",
                bootstrap,
                "group.id",
                channels.group("engine"),
                "enable.auto.commit",
                false,
                "auto.offset.reset",
                "earliest",
                "key.deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer",
                "default.api.timeout.ms",
                3000,
                "session.timeout.ms",
                10000,
                "heartbeat.interval.ms",
                2000))) {
      consumer = kafka;
      kafka.subscribe(List.of(channels.topic("trade")));
      while (running) {
        var records = kafka.poll(Duration.ofMillis(250));
        if (!records.isEmpty() && runtime.status() == TradingEngineService.Status.READY) {
          runtime.poll();
          if (runtime.status() == TradingEngineService.Status.READY) kafka.commitSync();
        }
      }
    } catch (org.apache.kafka.common.errors.WakeupException stopped) {
      /* 本实例正常关闭。 */
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(getClass()).error("Kafka 唤醒不可用，数据库追赶继续运行", failure);
    } finally {
      consumer = null;
      running = false;
    }
  }

  /** 只关闭自己的消费者，不重置消费组。 */
  @Override
  public synchronized void close() {
    running = false;
    var current = consumer;
    if (current != null) current.wakeup();
    if (thread != null)
      try {
        thread.join(5000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
  }
}
