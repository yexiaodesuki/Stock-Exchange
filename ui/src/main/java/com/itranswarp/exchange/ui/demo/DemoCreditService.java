/* 演示配资执行器：只提交稳定标识的内部配资命令，等待持久结果与引擎应用后才返回。 */
package com.itranswarp.exchange.ui.demo;

import com.fasterxml.jackson.databind.*;
import com.itranswarp.exchange.securities.SecuritiesChannels;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.output.*;
import com.itranswarp.exchange.util.JsonUtil;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.*;

public final class DemoCreditService implements AutoCloseable {
  private final OutputStore outputs;
  private final SecuritiesChannels channels;
  private final KafkaProducer<String, String> producer;
  private final String engine;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper json = new ObjectMapper();

  /** 连接参数来自证券环境配置，生产者只发 transfer Topic，不提供浏览器入口。 */
  public DemoCreditService(DataSource source, String bootstrap, String namespace, String engine) {
    outputs = new OutputStore(source);
    channels = new SecuritiesChannels(namespace);
    this.engine = engine;
    producer =
        new KafkaProducer<>(
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
                "org.apache.kafka.common.serialization.StringSerializer"));
  }

  /** 发送超时只重试同一个 uniqueId；持久结果存在但尚未安装时继续等待，不能重复配资。 */
  public ExecutionEnvelope execute(Submission request) throws Exception {
    if (!(request.command() instanceof CreditCash)
        && !(request.command() instanceof CreditPosition))
      throw new IllegalArgumentException("补齐用户只允许内部配资命令");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90), nextSend = 0;
    while (System.nanoTime() < deadline) {
      var result = outputs.result(request.userId(), request.uniqueId());
      if (result.isPresent() && applied() >= result.get().sequenceId()) return result.get();
      if (result.isEmpty() && System.nanoTime() >= nextSend) {
        try {
          producer
              .send(new ProducerRecord<>(channels.topic("transfer"), JsonUtil.writeJson(request)))
              .get(8, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw interrupted;
        } catch (Exception uncertain) {
          /* 不确定的发送只用原标识重试，由事件日志去重。 */
        }
        nextSend = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException("等待配资执行超时，可使用同一计划续跑");
  }

  /** 内部健康检查必须 READY；连接暂不可用只表示尚不能确认完成。 */
  private long applied() throws InterruptedException {
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(engine + "/internal/securities/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200
          ? json.readTree(response.body()).path("applied").asLong(-1)
          : -1;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw interrupted;
    } catch (Exception unavailable) {
      return -1;
    }
  }

  /** 只关闭本执行器的生产者，不停止业务应用或容器。 */
  @Override
  public void close() {
    producer.close(Duration.ofSeconds(3));
  }
}
