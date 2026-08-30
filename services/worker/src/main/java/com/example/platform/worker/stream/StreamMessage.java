package com.example.platform.worker.stream;

import com.example.platform.worker.event.DomainEvent;

public record StreamMessage(String recordId, DomainEvent event, long deliveryCount) {
}
