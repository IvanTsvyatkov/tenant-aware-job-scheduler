# Project Summary: Tenant-Aware Job Scheduler

## Table of Contents

- [What Was Built](#what-was-built)
- [Core Features Delivered](#core-features-delivered)
  - [1. Multi-Tenancy](#1-multi-tenancy-)
  - [2. Job Management API](#2-job-management-api-)
  - [3. Concurrency Control](#3-concurrency-control-)
  - [4. Idempotency](#4-idempotency-)
  - [5. Job Lifecycle](#5-job-lifecycle-)
  - [6. Real-Time Updates](#6-real-time-updates-)
  - [7. React UI](#7-react-ui-)
  - [8. Testing](#8-testing-)
- [Architecture Decisions](#architecture-decisions)
  - [1. Schema-per-Tenant](#1-schema-per-tenant)
  - [2. Optimistic Locking](#2-optimistic-locking)
  - [3. In-Memory Semaphores](#3-in-memory-semaphores)
  - [4. On-Demand Schema Creation](#4-on-demand-schema-creation)
  - [5. Fair Scheduling Algorithm](#5-fair-scheduling-algorithm)
- [Project Structure](#project-structure)
- [Key Files](#key-files)
  - [Backend](#backend)
  - [Frontend](#frontend)
  - [Configuration](#configuration)
- [Running the Application](#running-the-application)
  - [Quick Start (Single Command)](#quick-start-single-command)
  - [Manual Start (3 Terminals)](#manual-start-3-terminals)
  - [Verify Multi-Tenancy](#verify-multi-tenancy)
- [Testing](#testing)
  - [Run All Tests](#run-all-tests)
  - [Generate Coverage Report](#generate-coverage-report)
- [What Would Come Next](#what-would-come-next)
  - [With More Time (Next 8 Hours)](#with-more-time-next-8-hours)

---

## What Was Built

A full-stack multi-tenant job scheduling service with:
- **Backend**: Java 17, Spring Boot 3.2.5, Spring Data JPA
- **Database**: PostgreSQL 15, Flyway
- **Frontend**: React 18, Vite, Axios
- **Build**: Maven 3.9+
- **Testing**: JUnit 5, Mockito, H2
- **Infrastructure**: Docker Compose

### Core Features Delivered

#### 1. **Multi-Tenancy** ✅
- Schema-per-tenant architecture
- Dynamic schema creation on first job submission
- Complete data isolation between tenants
- Flyway migrations per tenant schema

> **Note on tenant resolution (POC scope):** The tenant is currently taken
> from the `X-Tenant-Id` HTTP header and interpolated into the schema name.
> This is acceptable for this proof-of-concept only. In the future,
> authentication will use JWT tokens and the tenant identity will be derived
> from a verified claim in the JWT rather than a client-supplied header. This
> also removes the current SQL-injection consideration around the raw
> tenant value, since the tenant will come from a signed, validated token.

> **Note on mocked tenants (POC scope):** The list of tenants is **mocked and
> hardcoded in the UI**, defined in the `TENANTS` constant in
> `frontend/src/App.jsx`. Adding a new tenant currently requires editing that
> constant by hand, since **tenant creation/provisioning is not supported in this
> iteration** of the project. There is no backend registration flow or
> self-service onboarding — the frontend simply drives the `X-Tenant-Id` value
> from this static list. In a production system this responsibility would most
> likely live in a **dedicated tenant-management microservice** (handling tenant
> lifecycle, provisioning, and identity), which the UI and backend would query
> instead of relying on a hardcoded list.

> **Note on SSE stream authorization (POC scope):** SSE stream subscription
> (`GET /jobs/stream` → `SseService.addEmitter`) is currently scoped by the
> `X-Tenant-Id` request header and is therefore **not authorization-enforced**.
> Because the header is client-supplied and untrusted, a caller could subscribe
> to another tenant's live job event stream. This shares the same root cause as
> the tenant-identity concern above. Under the planned JWT approach, the tenant
> is derived from a verified token claim rather than a header, so a client can
> only subscribe to its own tenant's event stream. No per-method validation is
> added in `SseService` now, as that would be security theater without a trusted
> identity source.

> **Note on database-level tenant isolation (POC scope):** All tenant schemas
> are currently accessed by a single shared PostgreSQL user, so isolation relies
> **entirely on the application** correctly setting `search_path` to the right
> `tenant_<id>` schema. The database provides no backstop: a missing, wrong, or
> un-reset `search_path` on a pooled connection could expose one tenant's data to
> another. A stronger, defense-in-depth approach would be a **dedicated database
> role per tenant**, granted privileges only on that tenant's schema, so the
> database itself refuses cross-tenant access — this would also contain the blast
> radius of the `search_path` SQL-injection consideration noted above. The
> trade-offs are additional role/credential management and per-tenant connection
> pooling (or `SET ROLE` per connection) instead of a single shared pool. The
> shared-user model is an acceptable simplification for this proof-of-concept,
> but per-tenant DB roles should be adopted for production.

#### 2. **Job Management API** ✅
- `POST /jobs` - Create jobs with idempotency guarantees
- `GET /jobs` - List all jobs for a tenant
- `GET /jobs/{id}` - Get specific job details
- `GET /jobs/stream` - SSE endpoint for real-time updates

#### 3. **Concurrency Control** ✅
Three-level enforcement using in-memory semaphores:
- **Global**: 5 concurrent jobs (configurable)
- **Per-tenant**: 2 concurrent jobs (configurable)
- **Per-target**: 2 concurrent jobs (configurable)

Atomically-acquired permits ensure no partial acquisition.

> **Note on single-instance limitation (POC scope):** These semaphores live in
> the **JVM's memory**, so they only coordinate concurrency within a **single
> service instance**. If we run **more than one instance** of the service (e.g.
> behind a load balancer or scaled horizontally), each instance keeps its own
> independent set of semaphores and is unaware of jobs running on the others.
> This breaks the caps — the effective concurrency becomes `cap × number of
> instances`, and we get **race conditions** where multiple instances
> simultaneously admit jobs that together exceed the intended global/per-tenant/
> per-target limits. To support horizontal scaling we would need a **shared,
> external coordination store** such as **Redis** (or Hazelcast/ZooKeeper),
> where every instance atomically reads and updates the concurrency counters so
> the caps are enforced cluster-wide rather than per-instance.

#### 4. **Idempotency** ✅
- Database unique constraint on `idempotency_key`
- Duplicate requests return same job (no double-enqueue)
- Tested under concurrent race conditions

#### 5. **Job Lifecycle** ✅
- States: `PENDING → RUNNING → SUCCEEDED | FAILED`
- Automatic retry on failure (max 3 retries)
- Optimistic locking prevents double-execution
- 10% simulated failure rate for testing

#### 6. **Real-Time Updates** ✅
- Server-Sent Events (SSE) stream per tenant
- Live job status updates pushed to UI
- Automatic reconnection handling

#### 7. **React UI** ✅
- Job submission form with tenant/target selection
- Live job list with real-time SSE updates
- **Cap-hit indicator**: Jobs show "⏸ Waiting (Cap Limit)" when delayed by concurrency caps
- Color-coded status badges
- Tenant switcher for viewing isolated data

#### 8. **Testing** ✅

**Frontend coverage (Vitest) — 32 tests:**

```
---------------------|---------
File                 | % Lines
---------------------|---------
All files            |   91.98
 src                 |   98.89
  App.jsx            |   98.85
  main.jsx           |     100
 src/components      |   88.75
  JobForm.jsx        |     100
  JobList.jsx        |   80.72
  JobStatusBadge.jsx |   93.25
 src/services        |     100
  api.js             |     100
---------------------|---------
```

**Backend coverage (JaCoCo) — `com.scheduler` (191 tests):**

| Package      | Line %        |
|--------------|---------------|
| `config`     | 92% (72/...)  |
| `controller` | 100% (72/72)  |
| `dto`        | 100% (25/25)  |
| `model`      | 92% (24/26)   |
| `repository` | 91% (22/24)   |
| `service`    | 91% (331/...) |
| Application  | 100% (1/1)    |

Overall backend: **92% lines (475/...)**.



## Architecture Decisions

### 1. Schema-per-Tenant
**Chosen**: Each tenant gets their own PostgreSQL schema (`tenant_{id}`)

**Why**: 
- Strong data isolation (no cross-tenant data leaks)
- Simple to understand and implement
- No row-level filtering overhead

**Tradeoff**: 
- Scales to ~1,000s of tenants
- Beyond that, consider database sharding

### 2. Optimistic Locking
**Chosen**: JPA `@Version` field for concurrent job execution

**Why**:
- Better throughput than pessimistic locking under low-to-moderate contention
- Simpler code (no explicit lock management)

**Tradeoff**:
- Under extreme contention, retries increase
- For very high contention, pessimistic locking may be better

### 3. In-Memory Semaphores
**Chosen**: `java.util.concurrent.Semaphore` for cap enforcement

**Why**:
- Fast (no network overhead)
- Simple implementation
- Low latency

**Tradeoff**:
- Caps reset on application restart
- Not distributed (single-instance only)
- For production, use Redis/Hazelcast

### 4. On-Demand Schema Creation
**Chosen**: Create schemas when tenants submit their first job

**Why**:
- No need to pre-register tenants
- Simpler startup (no schema discovery)

**Tradeoff**:
- First job per tenant has higher latency
- No validation that tenant exists before creation

### 5. Fair Scheduling Algorithm
**Chosen**: Round-robin with fair-share calculation

**Why**:
- Prevents tenant starvation
- Equal opportunity for all tenants
- Simple to reason about

**Implementation**:
- Calculate fair share: `available_capacity / active_tenants`
- Schedule oldest jobs first within each tenant
- Respects tenant-max cap

> **Note on choosing correct config values:** Picking sensible concurrency caps
> is **essential for the fair scheduling algorithm** to behave correctly:
>
> ```yaml
> scheduler:
>   concurrency:
>     global-max: 5   # must not exceed 50 — this is our current thread pool size
>     tenant-max: 2
>     target-max: 1
> ```
>
> - **`global-max`** must **not exceed the thread pool size (currently 50)**.
>   Setting it higher admits more jobs than there are worker threads, so the
>   extra jobs just queue on the pool and the "global" cap stops reflecting real
>   parallelism.
> - **`tenant-max`** should be `≤ global-max`, otherwise a single tenant could
>   consume the entire global budget and starve the others — defeating the fair
>   scheduling goal.
> - **`target-max`** should be `≤ tenant-max`, so no single downstream target
>   monopolizes a tenant's share.
>
> Keeping the relationship `target-max ≤ tenant-max ≤ global-max ≤ pool-size`
> ensures the fair-share calculation (`available_capacity / active_tenants`)
> distributes capacity evenly and no layer of the cap hierarchy is rendered
> meaningless.


## Project Structure

```
tenant-job-scheduler/
├── backend/
│   ├── src/main/
│   │   ├── java/com/scheduler/
│   │   │   ├── config/              # Tenant context, datasource routing, CORS
│   │   │   ├── controller/          # REST API endpoints
│   │   │   ├── dto/                 # Request/response objects
│   │   │   ├── model/               # JPA entities (Job, JobStatus)
│   │   │   ├── repository/          # Spring Data JPA repositories
│   │   │   ├── service/             # Business logic, scheduler, concurrency
│   │   │   └── JobSchedulerApplication.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/        # Flyway SQL scripts
│   ├── src/test/                    
│   └── pom.xml
├── frontend/
│   ├── src/
│   │   ├── components/              # JobForm, JobList, JobStatusBadge
│   │   ├── services/                # API client
│   │   └── App.jsx
│   └── package.json
├── docker-compose.yml                # PostgreSQL setup
├── README.md                         # This file
└── run.sh                            # Single-command startup script
```

## Key Files

### Backend
- **JobSchedulerApplication.java** - Main entry point
- **TenantContext.java** - ThreadLocal tenant storage
- **TenantInterceptor.java** - Extracts tenant from `X-Tenant-Id` header
- **DataSourceConfig.java** - Datasource setup / schema-per-tenant wiring
- **TenantConnectionProvider.java** - Dynamic per-tenant schema routing (`search_path`)
- **AsyncConfig.java** - Async / thread-pool configuration
- **WebConfig.java** - CORS + interceptor registration
- **FlywayMigrationService.java** - Per-tenant schema creation + migration
- **Job.java** - Entity with `@Version` for optimistic locking
- **JobStatus.java** - Job lifecycle status enum
- **JobRepository.java** - Spring Data JPA repository
- **JobService.java** - Idempotency logic, job CRUD
- **JobSchedulerService.java** - Scheduled polling, job execution
- **ConcurrencyCapManager.java** - Three-level semaphore enforcement
- **TargetService.java** - Simulated downstream execution
- **SseService.java** - Real-time event broadcasting
- **JobController.java** - REST API + SSE endpoints
- **ConfigController.java** - Exposes runtime config (e.g. concurrency caps)

### Frontend
- **App.jsx** - Root component with tenant switcher
- **JobForm.jsx** - Job submission form
- **JobList.jsx** - Live job list with SSE connection
- **JobStatusBadge.jsx** - Status badges with cap-hit indicator
- **api.js** - Axios HTTP client

### Configuration
- **application.yml** - Spring Boot config, datasource, concurrency caps
- **pom.xml** - Maven dependencies
- **docker-compose.yml** - PostgreSQL container
- **V1__create_jobs_table.sql** - Flyway migration

## Running the Application

### Quick Start (Single Command)
```bash
./run.sh
```
This starts PostgreSQL, backend, and frontend in the correct order.

### Manual Start (3 Terminals)

**Terminal 1: PostgreSQL**
```bash
docker-compose up
```

**Terminal 2: Backend**
```bash
cd backend
mvn spring-boot:run
```

**Terminal 3: Frontend**
```bash
cd frontend
npm install
npm run dev
```

Then open: **http://localhost:5173**

### Verify Multi-Tenancy

```bash
# Connect to PostgreSQL
docker exec -it job-scheduler-postgres psql -U job_scheduler_app -d job_scheduler

# List schemas (should see tenant_tenant1, tenant_tenant2, etc.)
\dn

# View jobs in tenant1's schema
SET search_path TO tenant_tenant1;
SELECT id, target_id, status, retry_count FROM jobs;
```

## Testing

### Run All Tests
```bash
cd backend
mvn test
```

**Result**: ✅ 191 tests passing, 92% coverage

### Generate Coverage Report
```bash
mvn test jacoco:report
open target/site/jacoco/index.html
```

## What Would Come Next

### With More Time (Next 8 Hours)
1. **Integration tests** and **LoadTests**  
2. **Logging and metrics** (Prometheus + Grafana)
3. **Admin API** - configure caps, pause/resume tenants
4. **Job cancellation** endpoint
5. **Distributed semaphores** (Redis-based)
6. Kafka-based **event-driven architecture** for job execution instead of querying a database table
7.  **JWT authentication**
8. **Horizontal scaling** (multiple scheduler instances)
9. **Handle restarts gracefully** (persist in-progress jobs, resume on restart)
