# IoT Telemetry — Engineering Context

This is the canonical context bundle for humans and coding agents. Read this file, the current spec under `spec/`, and relevant code before proposing changes.

## System purpose

A small industrial IoT backend used to learn and demonstrate:
- event-driven ingestion;
- Kafka partitioning and consumer semantics;
- database-backed idempotency;
- observability;
- load-driven design decisions;
- safe cloud deployment.

## Current boundaries

### Ingestion API
Owns request validation and Kafka publication acknowledgement.

### Kafka
Decouples ingestion from persistence/alert evaluation. `deviceId` is the message key.

### Consumer
Owns persistence and deterministic alert creation. Duplicate delivery must be harmless.

### PostgreSQL
System of record for V1. Database constraints are authoritative concurrency guarantees.

### Redis
Not yet part of the design. Add only after a measured latest-state read requirement.

### ML
Not yet part of the safety path. Predictive maintenance, if added, produces advisory signals; deterministic business/safety invariants remain outside the model.

## Invariants — do not violate

1. Never acknowledge an API request as accepted if Kafka publication failed.
2. Never rely on an LLM, prompt, or client to enforce telemetry business invariants.
3. Reusing the same event ID must be safe.
4. No in-memory lock may be the sole duplicate-prevention mechanism.
5. A duplicate event must not create duplicate alerts.
6. Tenant ID must participate in read boundaries.
7. Do not call security advisories proven vulnerabilities without investigation.
8. Do not claim load/performance improvements until measured.

## Spec-driven workflow

For any behavior change:

1. update the relevant spec/acceptance criterion first;
2. record a design decision when architecture changes;
3. give the coding agent the spec + this context + only relevant code;
4. implement the smallest change that satisfies the spec;
5. add/adjust tests that prove the acceptance criterion;
6. run deterministic build/test/security verification;
7. capture measured results before writing an interview STAR story.

## Context engineering rules

Give agents **bounded context**, not the entire repository by default.

Minimum useful context:
- current spec;
- this file;
- files directly involved in the change;
- failing test/log evidence;
- relevant ADR.

Do not provide:
- secrets;
- unrelated services;
- production credentials;
- large log dumps when a focused excerpt is enough.

Prompt agents to return:
- assumption(s);
- files changed;
- acceptance criteria addressed;
- tests added/run;
- unresolved risk.

## Engineering Agent role

The existing Engineering Agent is a verifier, not the source of truth for product behavior.

Use it to collect evidence from:
- Git diff/source;
- build;
- unit/integration tests;
- fixed security scanners;
- structured review output.

The product spec and deterministic tests define correctness. The model does not approve its own work.

## Interview evidence discipline

Only claim:
- behavior implemented in the repository;
- tests actually run;
- metrics actually measured;
- failures actually observed.

The eventual Amazon Leadership Principle story should come from a real design decision, failure, bottleneck, or measured improvement produced while building this system.
