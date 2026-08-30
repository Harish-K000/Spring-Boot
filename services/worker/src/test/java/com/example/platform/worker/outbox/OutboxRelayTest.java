package com.example.platform.worker.outbox;

import com.example.platform.worker.event.DomainEvent;
import com.example.platform.worker.stream.EventStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class OutboxRelayTest {

    private final OutboxStore store = mock(OutboxStore.class);
    private final EventStream stream = mock(EventStream.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(store, stream, 10, metrics);

    @Test
    void marksEventPublishedOnlyAfterRedisAcceptsIt() {
        ClaimedOutboxEvent event = event();
        given(store.claimBatch(10)).willReturn(List.of(event));

        relay.relay();

        verify(stream).publish(event.event());
        verify(store).markPublished(event);
        verify(store, never()).markFailed(any(), any());
        assertThat(io.micrometer.core.instrument.search.Search.in(metrics)
                .name("platform.outbox.relay").tag("outcome", "published")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void releasesFailedPublicationForBackoffRetry() {
        ClaimedOutboxEvent event = event();
        RuntimeException failure = new RuntimeException("redis unavailable");
        given(store.claimBatch(10)).willReturn(List.of(event));
        doThrow(failure).when(stream).publish(event.event());

        relay.relay();

        verify(store).markFailed(event, failure);
        verify(store, never()).markPublished(any());
    }

    private ClaimedOutboxEvent event() {
        DomainEvent event = new DomainEvent(UUID.randomUUID(), "orders", "ORDER",
                UUID.randomUUID(), "ORDER_CREATED", "{}", Instant.now());
        return new ClaimedOutboxEvent(event, OutboxSource.ORDERS, UUID.randomUUID(), 0);
    }
}
