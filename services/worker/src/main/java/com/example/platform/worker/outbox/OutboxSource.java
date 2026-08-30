package com.example.platform.worker.outbox;

public enum OutboxSource {
    ORDERS("orders", "orders.outbox_events"),
    PAYMENTS("payments", "payments.outbox_events");

    private final String eventSource;
    private final String table;

    OutboxSource(String eventSource, String table) {
        this.eventSource = eventSource;
        this.table = table;
    }

    public String eventSource() {
        return eventSource;
    }

    public String table() {
        return table;
    }
}
