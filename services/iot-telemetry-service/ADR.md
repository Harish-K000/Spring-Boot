# IoT Telemetry — Architecture Decision Log

## ADR-001 — Kafka between ingestion and persistence

**Decision:** REST ingestion publishes accepted events to Kafka; persistence and alerting run asynchronously.

**Why:** Device bursts should not couple API response time to database/alert processing. Kafka gives buffering, replay, and partitioned processing.

**Trade-off:** Adds broker operations, consumer lag, duplicate-delivery handling, and eventual consistency.

**Rejected V1 alternative:** synchronous REST → PostgreSQL → alerts. Simpler, but couples ingestion availability and latency to all downstream work.

---

## ADR-002 — deviceId as Kafka key

**Decision:** Key telemetry by `deviceId`.

**Why:** Preserve per-device partition order while still distributing different devices across partitions.

**Trade-off:** A hot device can create a hot partition. Late device timestamps are still possible even with Kafka order.

---

## ADR-003 — eventId is the idempotency identity

**Decision:** Producer supplies a stable event UUID and PostgreSQL enforces uniqueness.

**Why:** Kafka and network retries can deliver the same logical event more than once.

**Trade-off:** Producer must preserve event ID across retries and the consumer needs an atomic database duplicate boundary.

---

## ADR-004 — deterministic thresholds before ML

**Decision:** V1 alerting uses explicit thresholds.

**Why:** It gives deterministic, testable correctness before introducing probabilistic maintenance predictions.

**Trade-off:** Fixed rules cannot model complex degradation patterns. Predictive ML is a later advisory layer.

---

## ADR-005 — PostgreSQL first; Redis only after measurement

**Decision:** Latest-state reads initially use PostgreSQL.

**Why:** Avoid adding a cache before proving a read-latency or throughput bottleneck.

**Trade-off:** PostgreSQL may become the read bottleneck at scale. Phase 3 benchmarks Redis against the measured baseline before adoption.

---

## ADR-006 — AWS deployment comes last

**Decision:** Establish local correctness, tests, and load evidence before AWS infrastructure.

**Why:** Cloud deployment should validate portability/operations, not hide application-level correctness problems.

**Target:** low-cost proof-of-concept using current AWS Free Tier/credits where eligible, with CloudWatch observability and no managed service chosen purely for resume keywords.
