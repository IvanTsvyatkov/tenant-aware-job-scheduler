# CLAUDE.md — Project Memory: Tenant-Aware Job Scheduler

> Persistent project memory. Durable facts, conventions, and constraints so
> future sessions have context without re-reading the whole codebase.
> Full documentation lives in `README.md`.

## Stack
- Backend: Java 17, Spring Boot 3.2.5, Spring Data JPA, Maven 3.9+
- DB: PostgreSQL 15 + Flyway (schema-per-tenant)
- Frontend: React 18, Vite, Axios; tests via Vitest
- Backend tests: JUnit 5, Mockito, H2, JaCoCo
- Infra: Docker Compose (PostgreSQL)

## Architecture (key decisions)
- Schema-per-tenant: each tenant → `tenant_{id}` schema, created on first job submit.
- Tenant resolved from `X-Tenant-Id` header via `TenantInterceptor` → `TenantContext` (ThreadLocal).
- `TenantConnectionProvider` sets `search_path` per request.
- Optimistic locking via JPA `@Version` on `Job`.
- Concurrency caps enforced by in-memory `Semaphore` in `ConcurrencyCapManager` (global/tenant/target).
- Fair scheduling: round-robin, `available_capacity / active_tenants`, oldest-first.
- SSE (`GET /jobs/stream`) for real-time updates via `SseService`.
- Idempotency: DB unique constraint on `idempotency_key`.
- Job lifecycle: `PENDING → RUNNING → SUCCEEDED | FAILED`, max 3 retries, 10% simulated failure.

## Config invariants (do not violate)
`target-max ≤ tenant-max ≤ global-max ≤ pool-size (currently 50)`
- Defaults: global-max=5, tenant-max=2, target-max=1.

## Known POC limitations (intentional — don't "fix" without discussion)
- Tenant identity from untrusted `X-Tenant-Id` header; JWT-claim-based auth is the planned replacement.
- SSE stream not authorization-enforced (same header root cause).
- Single shared DB user; no per-tenant DB role isolation yet.
- Semaphores are in-JVM → single-instance only; needs Redis/Hazelcast for horizontal scaling.
- Tenant list hardcoded in `frontend/src/App.jsx` `TENANTS` constant (no provisioning flow).
- On-demand schema creation; no tenant pre-validation.

## Key files
- Backend: `TenantContext`, `TenantInterceptor`, `TenantConnectionProvider`,
  `DataSourceConfig`, `AsyncConfig`, `WebConfig`, `FlywayMigrationService`,
  `Job`, `JobStatus`, `JobRepository`, `JobService`, `JobSchedulerService`,
  `ConcurrencyCapManager`, `TargetService`, `SseService`, `JobController`, `ConfigController`.
- Frontend: `App.jsx`, `JobForm.jsx`, `JobList.jsx`, `JobStatusBadge.jsx`, `api.js`.

## Commands
- Run all: `./run.sh`
- Backend: `cd backend && mvn spring-boot:run`
- Frontend: `cd frontend && npm install && npm run dev` (http://localhost:5173)
- Tests + coverage: `cd backend && mvn test jacoco:report`

## Test status
- Backend: 191 tests, ~92% line coverage.
- Frontend: 32 tests, ~92% line coverage.

## Roadmap (next)
Integration/load tests, Prometheus+Grafana, Admin API, job cancellation,
distributed semaphores (Redis), Kafka event-driven execution, JWT auth,
horizontal scaling, graceful restart/resume.
