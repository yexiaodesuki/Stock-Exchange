/* 交易提交服务：持久登记幂等请求、发送 Kafka 并查询执行结果；发送确认不等于执行完成。 */
package com.itranswarp.exchange.service;

import com.itranswarp.exchange.securities.SecuritiesChannels;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.output.OutputStore;
import com.itranswarp.exchange.util.JsonUtil;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

public final class SendEventService implements AutoCloseable {
  private final JdbcTemplate jdbc;
  private final OutputStore outputs;
  private final SecuritiesChannels channels;
  private final TradingEngineApiProxyService engineProxy;
  private final org.springframework.transaction.support.TransactionTemplate transaction;
  private final KafkaProducer<String, String> producer;

  /** 事务和 Kafka 参数保持原值；该服务是唯一请求登记与发送入口。 */
  public SendEventService(
      DataSource source,
      String bootstrap,
      String namespace,
      TradingEngineApiProxyService engineProxy) {
    this.engineProxy = engineProxy;
    jdbc = new JdbcTemplate(source);
    outputs = new OutputStore(source);
    channels = new SecuritiesChannels(namespace);
    transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    producer =
        new KafkaProducer<>(
            Map.of(
                "bootstrap.servers",
                bootstrap,
                "acks",
                "all",
                "max.block.ms",
                2000,
                "request.timeout.ms",
                2000,
                "delivery.timeout.ms",
                4000,
                "key.serializer",
                "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer",
                "org.apache.kafka.common.serialization.StringSerializer"));
  }

  /** 先登记幂等请求再发送；网络不确定性仍返回原 requestId 和 503。 */
  public ResponseEntity<?> submit(long user, String key, Command command) {
    engineProxy.internal("/status");
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
      throw new IllegalArgumentException("幂等键无效");
    String id = "u" + user + ":" + OutputStore.hash(key);
    var request = new Submission(2, 1, id, user, command);
    String fingerprint =
        OutputStore.hash(
            JsonUtil.writeJson(
                com.itranswarp.exchange.securities.persistence.EventJournal.canonical(command)));
    transaction.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO api_requests VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE"
                  + " requestId=requestId",
              id,
              user,
              fingerprint,
              System.currentTimeMillis());
          var old =
              jdbc.queryForMap(
                  "SELECT userId,commandHash FROM api_requests WHERE requestId=? FOR UPDATE", id);
          if (((Number) old.get("userId")).longValue() != user
              || !fingerprint.equals(old.get("commandHash")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "幂等键内容冲突");
        });
    try {
      producer
          .send(new ProducerRecord<>(channels.topic("sequence"), JsonUtil.writeJson(request)))
          .get(5, java.util.concurrent.TimeUnit.SECONDS);
      return ResponseEntity.accepted().body(Map.of("requestId", id, "status", "SUBMITTED"));
    } catch (Exception uncertain) {
      return ResponseEntity.status(503)
          .body(
              Map.of(
                  "requestId", id, "status", "SUBMISSION_UNCERTAIN", "message", "请用同一幂等键重试或查询结果"));
    }
  }

  /** 仅本人请求可查询；持久结果和已应用位置同时满足才返回完成。 */
  public ResponseEntity<?> result(long user, String request) {
    if (!request.startsWith("u" + user + ":"))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var status = engineProxy.internal("/status");
    var found = outputs.result(user, request);
    if (found.isPresent() && found.get().sequenceId() <= status.path("applied").asLong()) {
      var value = found.get();
      var response = new LinkedHashMap<String, Object>();
      response.put("requestId", request);
      response.put("status", "COMPLETED");
      response.put("accepted", value.accepted());
      response.put("reason", value.reason());
      response.put("orderId", value.orderId() == null ? null : Long.toString(value.orderId()));
      response.put("sequenceId", Long.toString(value.sequenceId()));
      return ResponseEntity.ok(response);
    }
    var rows =
        jdbc.queryForList(
            "SELECT e.data FROM unique_events u JOIN events e ON u.sequenceId=e.sequenceId WHERE"
                + " u.uniqueId=?",
            request);
    if (rows.isEmpty()
        || JsonUtil.readJson((String) rows.get(0).get("data"), SequencedEvent.class).userId()
            != user) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return ResponseEntity.accepted().body(Map.of("requestId", request, "status", "PENDING"));
  }

  /** 只释放本服务生产者，不修改 Topic 和消费组。 */
  @Override
  public void close() {
    producer.close(Duration.ofSeconds(2));
  }
}
