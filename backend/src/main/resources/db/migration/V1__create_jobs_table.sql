CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    payload TEXT,
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT
);

-- Composite index: covers findByStatus / countByStatus / findPendingJobsOrderedByCreation (status + ORDER BY created_at)
CREATE INDEX idx_jobs_status_created_at ON jobs(status, created_at);

-- Composite index: covers findByTenantIdAndStatus / countByTenantIdAndStatus
CREATE INDEX idx_jobs_tenant_status ON jobs(tenant_id, status);


