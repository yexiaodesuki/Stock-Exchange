/* Kafka 确认测试：受控 Future 替代 broker，不创建 Topic、不发送真实消息。 */
package com.itranswarp.exchange.monitoring;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import com.itranswarp.exchange.message.event.TransferEvent;
import com.itranswarp.exchange.messaging.MessageTypes;
import com.itranswarp.exchange.messaging.Messaging;
import com.itranswarp.exchange.messaging.MessagingFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;

class KafkaSendMonitoringTest {
    /** send 返回先不算确认；Future 成功和失败才分别增加对应结果。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { true, false })
    void asynchronousAcknowledgementIsSeparateFromReturn(boolean failure) {
        var registry = new SimpleMeterRegistry();
        var future = new CompletableFuture<SendResult<String, String>>();
        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of());
        var template = new KafkaTemplate<String, String>(producerFactory) {
            /** 返回受控结果，不创建 KafkaProducer。 */
            @Override public CompletableFuture<SendResult<String, String>> send(String topic, String data) { return future; }
        };
        var factory = new MessagingFactory();
        ReflectionTestUtils.setField(factory, "monitoring", new PipelineMonitoring(registry));
        ReflectionTestUtils.setField(factory, "messageTypes", new MessageTypes());
        ReflectionTestUtils.setField(factory, "kafkaTemplate", template);
        try {
            var producer = factory.createMessageProducer(Messaging.Topic.SEQUENCE, TransferEvent.class);
            producer.sendMessage(new TransferEvent());
            assertEquals(1, registry.get("exchange.pipeline.operations").tags("stage", "kafka_send_SEQUENCE", "result", "returned").counter().count());
            assertNull(registry.find("exchange.pipeline.operations").tags("stage", "kafka_send_SEQUENCE", "result", "acknowledged").counter());
            if (failure) future.completeExceptionally(new IllegalStateException("隔离 broker 失败"));
            else future.complete(null);
            assertEquals(1, registry.get("exchange.pipeline.operations").tags("stage", "kafka_send_SEQUENCE", "result", failure ? "async_failure" : "acknowledged").counter().count());
        } finally { template.destroy(); producerFactory.destroy(); registry.close(); }
    }
}
