package com.example.platform.worker.stream;

import com.example.platform.worker.event.DomainEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RedisEventStream implements EventStream {

    private static final Logger log = LoggerFactory.getLogger(RedisEventStream.class);
    private final StringRedisTemplate redis;
    private final String streamKey;
    private final String group;
    private final String consumerName;
    private final String deadLetterKey;
    private final Duration pendingIdle;
    private final MeterRegistry metrics;
    private final AtomicBoolean groupReady = new AtomicBoolean();

    public RedisEventStream(
            StringRedisTemplate redis,
            @Value("${worker.stream.key:platform:domain-events}") String streamKey,
            @Value("${worker.stream.group:platform-workers}") String group,
            @Value("${worker.stream.consumer:platform-worker}") String consumerName,
            @Value("${worker.stream.dead-letter-key:platform:domain-events:dead-letter}") String deadLetterKey,
            @Value("${worker.stream.pending-idle-seconds:10}") long pendingIdleSeconds,
            MeterRegistry metrics) {
        this.redis = redis;
        this.streamKey = streamKey;
        this.group = group;
        this.consumerName = consumerName;
        this.deadLetterKey = deadLetterKey;
        this.pendingIdle = Duration.ofSeconds(Math.max(1, pendingIdleSeconds));
        this.metrics = metrics;
    }

    @Override
    public void publish(DomainEvent event) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("id", event.id().toString());
        body.put("source", event.source());
        body.put("aggregateType", event.aggregateType());
        body.put("aggregateId", event.aggregateId().toString());
        body.put("eventType", event.eventType());
        body.put("payload", event.payload());
        body.put("occurredAt", event.occurredAt().toString());
        redis.opsForStream().add(streamKey, body);
    }

    @Override
    public List<StreamMessage> read(int batchSize) {
        ensureGroup();
        Consumer consumer = Consumer.from(group, consumerName);
        StreamReadOptions options = StreamReadOptions.empty().count(batchSize);

        // Reclaim stale pending records from any crashed or slow consumer before accepting new work.
        PendingMessages pending = redis.opsForStream().pending(
                streamKey, group, Range.unbounded(), batchSize);
        Map<String, Long> deliveryCounts = new HashMap<>();
        RecordId[] staleIds = pending.stream()
                .filter(message -> message.getElapsedTimeSinceLastDelivery().compareTo(pendingIdle) >= 0)
                .peek(message -> deliveryCounts.put(
                        message.getIdAsString(), message.getTotalDeliveryCount() + 1))
                .map(message -> message.getId())
                .toArray(RecordId[]::new);
        List<MapRecord<String, Object, Object>> records = staleIds.length == 0
                ? List.of()
                : redis.opsForStream().claim(
                        streamKey, group, consumerName, pendingIdle, staleIds);
        if (records == null || records.isEmpty()) {
            records = redis.opsForStream().read(
                    consumer, options,
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
        }
        if (records == null) {
            return List.of();
        }
        List<StreamMessage> messages = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            long deliveryCount = deliveryCounts.getOrDefault(record.getId().getValue(), 1L);
            try {
                messages.add(toMessage(record, deliveryCount));
            } catch (RuntimeException decodeFailure) {
                try {
                    deadLetterMalformed(record, deliveryCount, decodeFailure);
                    acknowledge(record.getId().getValue());
                    metrics.counter("platform.events.consumed",
                            "outcome", "malformed_dead_lettered",
                            "source", "unknown").increment();
                    log.error("Dead-lettered malformed Redis record {}",
                            record.getId().getValue(), decodeFailure);
                } catch (RuntimeException deadLetterFailure) {
                    decodeFailure.addSuppressed(deadLetterFailure);
                    throw decodeFailure;
                }
            }
        }
        return messages;
    }

    @Override
    public void deadLetter(StreamMessage message, RuntimeException failure) {
        DomainEvent event = message.event();
        Map<String, String> body = eventBody(event);
        body.put("originalRecordId", message.recordId());
        body.put("deliveryCount", Long.toString(message.deliveryCount()));
        body.put("failureType", failure.getClass().getSimpleName());
        body.put("failureMessage", truncatedMessage(failure));
        body.put("failedAt", Instant.now().toString());
        redis.opsForStream().add(deadLetterKey, body);
    }

    @Override
    public void acknowledge(String recordId) {
        redis.opsForStream().acknowledge(streamKey, group, recordId);
    }

    private void ensureGroup() {
        if (groupReady.get()) {
            return;
        }
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
                    redis.getStringSerializer().serialize(streamKey),
                    group,
                    ReadOffset.from("0-0"),
                    true));
        } catch (DataAccessException ex) {
            if (ex.getMessage() == null || !ex.getMessage().contains("BUSYGROUP")) {
                throw ex;
            }
        }
        groupReady.set(true);
    }

    private StreamMessage toMessage(MapRecord<String, Object, Object> record, long deliveryCount) {
        Map<Object, Object> value = record.getValue();
        DomainEvent event = new DomainEvent(
                UUID.fromString(required(value, "id")),
                required(value, "source"),
                required(value, "aggregateType"),
                UUID.fromString(required(value, "aggregateId")),
                required(value, "eventType"),
                required(value, "payload"),
                Instant.parse(required(value, "occurredAt")));
        return new StreamMessage(record.getId().getValue(), event, deliveryCount);
    }

    private Map<String, String> eventBody(DomainEvent event) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("id", event.id().toString());
        body.put("source", event.source());
        body.put("aggregateType", event.aggregateType());
        body.put("aggregateId", event.aggregateId().toString());
        body.put("eventType", event.eventType());
        body.put("payload", event.payload());
        body.put("occurredAt", event.occurredAt().toString());
        return body;
    }

    private void deadLetterMalformed(MapRecord<String, Object, Object> record,
                                     long deliveryCount,
                                     RuntimeException failure) {
        Map<String, String> body = new LinkedHashMap<>();
        record.getValue().forEach((key, value) ->
                body.put("raw_" + key, value == null ? "null" : value.toString()));
        body.put("originalRecordId", record.getId().getValue());
        body.put("deliveryCount", Long.toString(deliveryCount));
        body.put("failureType", failure.getClass().getSimpleName());
        body.put("failureMessage", truncatedMessage(failure));
        body.put("failedAt", Instant.now().toString());
        redis.opsForStream().add(deadLetterKey, body);
    }

    private String truncatedMessage(RuntimeException failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private String required(Map<Object, Object> value, String key) {
        Object field = value.get(key);
        if (field == null) {
            throw new IllegalArgumentException("Redis event is missing " + key);
        }
        return field.toString();
    }
}
