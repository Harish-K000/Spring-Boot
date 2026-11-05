# Coding Agent Instructions — IoT Telemetry

Before changing code, read:
1. `CONTEXT.md`
2. the active file in `spec/`
3. `ADR.md`
4. only the implementation/test files relevant to the acceptance criterion.

Do not invent requirements.

For every change:
- name the acceptance criterion(s) addressed;
- state assumptions;
- keep the diff minimal;
- preserve backend validation and DB-backed idempotency;
- add or update deterministic tests;
- do not add Redis, ML, AWS resources, or new infrastructure unless the spec/ADR explicitly requires it;
- report tests run and unresolved risks.

Generated code is a proposal. Human review plus deterministic build/test/security evidence decides readiness.
