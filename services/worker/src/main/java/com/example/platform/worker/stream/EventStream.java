package com.example.platform.worker.stream;

import com.example.platform.worker.event.DomainEvent;

import java.util.List;

public interface EventStream {
    void publish(DomainEvent event);
    List<StreamMessage> read(int batchSize);
    void deadLetter(StreamMessage message, RuntimeException failure);
    void acknowledge(String recordId);
}
