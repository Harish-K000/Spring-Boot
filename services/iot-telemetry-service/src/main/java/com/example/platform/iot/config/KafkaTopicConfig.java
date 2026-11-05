package com.example.platform.iot.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {
    @Bean
    NewTopic telemetryTopic(@Value("${iot.kafka.telemetry-topic:iot.telemetry.v1}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}
