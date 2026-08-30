package com.example.platform.worker.consumer;

import com.example.platform.worker.event.DomainEvent;
import com.example.platform.worker.stream.EventStream;
import com.example.platform.worker.stream.StreamMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class DomainEventConsumerTest {

    private final EventStream stream = mock(EventStream.class);
    private final EventProcessingService processor = mock(EventProcessingService.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final DomainEventConsumer consumer = new DomainEventConsumer(stream, processor, 5, 5, metrics);

    @Test
    void acknowledgesOnlyAfterDatabaseProcessingCommits() {
        StreamMessage message = message();
        given(stream.read(5)).willReturn(List.of(message));

        consumer.consume();

        var order = inOrder(processor, stream);
        order.verify(processor).process(message.event());
        order.verify(stream).acknowledge(message.recordId());
    }

    @Test
    void leavesFailedMessagePendingForRedisRedelivery() {
        StreamMessage message = message();
        given(stream.read(5)).willReturn(List.of(message));
        doThrow(new RuntimeException("handler failed")).when(processor).process(message.event());

        consumer.consume();

        verify(stream, never()).acknowledge(anyString());
    }

    @Test
    void deadLettersAndAcknowledgesPoisonMessageAtDeliveryLimit() {
        StreamMessage message = message(5);
        RuntimeException failure = new RuntimeException("handler failed");
        given(stream.read(5)).willReturn(List.of(message));
        doThrow(failure).when(processor).process(message.event());

        consumer.consume();

        var order = inOrder(stream);
        order.verify(stream).deadLetter(message, failure);
        order.verify(stream).acknowledge(message.recordId());
    }

    private StreamMessage message() {
        return message(1);
    }

    private StreamMessage message(long deliveryCount) {
        DomainEvent event = new DomainEvent(UUID.randomUUID(), "payments", "PAYMENT",
                UUID.randomUUID(), "PAYMENT_CAPTURED", "{}", Instant.now());
        return new StreamMessage("1-0", event, deliveryCount);
    }
}
