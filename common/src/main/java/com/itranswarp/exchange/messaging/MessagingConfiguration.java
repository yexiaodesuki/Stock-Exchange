/* Kafka 工厂：原生指标随客户端创建和关闭绑定，不改变消费策略。 */
package com.itranswarp.exchange.messaging;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@EnableKafka
@Configuration
public class MessagingConfiguration {

    final Logger logger = LoggerFactory.getLogger(getClass());

    Map<String, Object> producerConfigs(String bootstrapServers) {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return configs;
    }

    Map<String, Object> consumerConfigs(String bootstrapServers, int batchSize) {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Integer.valueOf(batchSize));
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return configs;
    }

    @Bean
    public KafkaAdmin admin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return new KafkaAdmin(configs);
    }

    /** 绑定原生生产者指标，保留原有生产者参数。 */
    @Bean
    public ProducerFactory<String, String> producerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        logger.info("init kafka producer from bootstrap servers: {}", bootstrapServers);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(producerConfigs(bootstrapServers));
        registries.ifAvailable(registry -> factory.addListener(new org.springframework.kafka.core.MicrometerProducerListener<>(registry)));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(@Autowired ProducerFactory<String, String> producerFactory) {
        logger.info("init kafka template...");
        return new KafkaTemplate<>(producerFactory);
    }

    /** 绑定真实客户端分区 lag 等指标，不将其解释为业务执行位置。 */
    @Bean
    public ConsumerFactory<String, String> consumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.max-poll-records}") int batchSize,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        logger.info("init kafka consumer from bootstrap servers: {}, batch-size: {}", bootstrapServers,
                Integer.valueOf(batchSize));
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(consumerConfigs(bootstrapServers, batchSize));
        registries.ifAvailable(registry -> factory.addListener(new org.springframework.kafka.core.MicrometerConsumerListener<>(registry)));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            @Autowired ConsumerFactory<String, String> consumerFactory) {
        logger.info("init concurrent kafka listener container factory...");
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(Integer.valueOf(1));
        factory.setBatchListener(Boolean.TRUE);
        return factory;
    }
}
