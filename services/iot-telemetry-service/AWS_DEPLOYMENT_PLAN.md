# AWS Deployment Plan — IoT Telemetry

AWS is the final phase, after local correctness and load evidence.

## Goal

Demonstrate that the same service can run on AWS with observable, low-cost infrastructure without changing the product invariants.

## Free-Tier-aware target

Current AWS Free Tier eligibility depends on account age and plan. For an eligible new account, AWS currently provides signup credits and a time-limited Free plan. We will check the account's live Free Tier page before provisioning anything.

### Initial proof-of-concept

- **EC2**: run the Spring Boot service and a single-node Kafka broker in Docker.
- **Amazon RDS for PostgreSQL**: Single-AZ PostgreSQL using an eligible micro class if the account offer supports it.
- **Amazon CloudWatch**: application logs, JVM/host metrics, and alarms.
- **AWS Systems Manager Parameter Store**: configuration/secrets that should not live in Git.
- **ECR**: optional container registry if it fits the account's current credits/allowances.
- **GitHub Actions + AWS OIDC**: later deployment automation without long-lived AWS access keys.

## Why not start with ECS/MSK

Managed services are valuable, but they add cost and operational variables before the application has a measured need. For the first cloud proof-of-concept, the goal is portability and observability, not maximum managed-service adoption.

After load testing we can decide whether to migrate:
- application → ECS/Fargate;
- Kafka → Amazon MSK;
- latest-state cache → ElastiCache/Redis.

Those migrations must be justified by measured throughput, availability, or operational requirements.

## Cost guardrails

Before provisioning:
1. verify Free Tier/credit eligibility in the actual AWS account;
2. create a budget and billing alert;
3. use one region;
4. avoid NAT Gateway for the first demo;
5. use Single-AZ resources;
6. tag every resource with `project=iot-telemetry`;
7. document a one-command/one-runbook teardown;
8. never assume a service is free because it used to be part of an older Free Tier.

## Deployment evidence to capture

- container startup and health check;
- API request → Kafka → PostgreSQL trace;
- CloudWatch logs;
- custom telemetry counters;
- p95 ingestion latency;
- consumer lag during a controlled load test;
- RDS write behavior;
- approximate cost/credit consumption for the test window.

The AWS phase should create new evidence for an interview story, not new claims without measurement.
