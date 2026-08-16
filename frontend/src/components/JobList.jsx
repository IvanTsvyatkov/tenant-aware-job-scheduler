import { useState, useEffect, useRef } from 'react';
import { jobApi } from '../services/api';
import JobStatusBadge from './JobStatusBadge';

function JobList({ tenantId, tenant }) {
  const [jobs, setJobs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const currentTenantRef = useRef(tenantId);

  // Keep ref updated
  useEffect(() => {
    currentTenantRef.current = tenantId;
  }, [tenantId]);

  // Fetch jobs when tenant changes
  useEffect(() => {
    fetchJobs();
  }, [tenantId]);

  // Listen for job updates via custom events - ONLY ONCE
  useEffect(() => {
    console.log('Setting up job-update event listener');

    const handleJobUpdate = (event) => {
      const job = event.detail;
      console.log('EVENT RECEIVED:', job.id, job.status, 'Tenant:', job.tenantId, 'Current:', currentTenantRef.current);

      // Use ref to get current tenant, not closure value
      if (job.tenantId !== currentTenantRef.current) {
        console.log('SKIPPING - wrong tenant');
        return;
      }

      console.log('APPLYING UPDATE to state');

      setJobs((prevJobs) => {
        console.log('setJobs callback - prevJobs length:', prevJobs.length);
        const index = prevJobs.findIndex((j) => j.id === job.id);
        console.log('Job index in array:', index);

        let newJobs;
        if (index >= 0) {
          newJobs = [...prevJobs];
          newJobs[index] = job;
          console.log('Updated existing job at index', index);
        } else {
          newJobs = [job, ...prevJobs];
          console.log('Adding new job to beginning');
        }

        console.log('NEW JOBS LENGTH:', newJobs.length);
        return newJobs;
      });
    };

    window.addEventListener('job-update', handleJobUpdate);
    console.log('Event listener registered');

    return () => {
      console.log('Removing job-update event listener');
      window.removeEventListener('job-update', handleJobUpdate);
    };
  }, []); // Empty deps - listener stays registered forever

  // Setup SSE connections once for all tenants
  useEffect(() => {
    const tenants = ['tenant1', 'tenant2', 'tenant3'];
    const controllers = [];

    tenants.forEach(tenant => {
      const controller = new AbortController();
      controllers.push(controller);
      connectSSE(tenant, controller);
    });

    // Cleanup all connections on unmount
    return () => {
      controllers.forEach(c => c.abort());
    };
  }, []); // Only once on mount

  const connectSSE = async (tenantId, controller) => {
    try {
      const response = await fetch(jobApi.getSSEUrl(), {
        headers: {
          'X-Tenant-Id': tenantId,
          'Accept': 'text/event-stream',
        },
        signal: controller.signal,
      });

      if (!response.ok) {
        throw new Error(`SSE connection failed: ${response.status}`);
      }

      console.log(`[${tenantId}] SSE connected`);

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message'; // Move outside while loop to persist across chunks

      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          console.log(`[${tenantId}] SSE stream ended`);
          break;
        }

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.substring(6).trim();
            console.log(`[${tenantId}] Event type:`, currentEvent);
          } else if (line.startsWith('data:')) {
            const data = line.substring(5).trim();

            if (currentEvent === 'job-update') {
              try {
                const jobData = JSON.parse(data);
                console.log(`[${tenantId}] Received job update:`, jobData.id, jobData.status);

                // Dispatch custom event
                console.log(`[${tenantId}] Dispatching custom event`);
                window.dispatchEvent(new CustomEvent('job-update', { detail: jobData }));
                console.log(`[${tenantId}] Custom event dispatched`);
              } catch (e) {
                console.error(`[${tenantId}] Error parsing job update:`, e, 'Data:', data);
              }
            } else {
              console.log(`[${tenantId}] Non-job-update event:`, currentEvent);
            }

            currentEvent = 'message';
          }
        }
      }
    } catch (err) {
      if (err.name !== 'AbortError') {
        console.error(`[${tenantId}] SSE error:`, err);
        // Auto-reconnect after 3 seconds
        if (!controller.signal.aborted) {
          setTimeout(() => connectSSE(tenantId, controller), 3000);
        }
      }
    }
  };

  const fetchJobs = async () => {
    try {
      setLoading(true);
      console.log('Fetching jobs for tenant:', tenantId);
      const response = await jobApi.getJobs(tenantId);
      const sortedJobs = response.data.sort((a, b) =>
        new Date(b.createdAt) - new Date(a.createdAt)
      );
      console.log('Fetched jobs:', sortedJobs.length);
      setJobs(sortedJobs);
      setLoading(false);
    } catch (err) {
      console.error('Error fetching jobs:', err);
      setError('Failed to load jobs');
      setLoading(false);
    }
  };

  const isCapHit = (job) => {
    if (job.status !== 'PENDING') return false;
    const now = new Date();
    const created = new Date(job.createdAt);
    const ageSeconds = (now - created) / 1000;
    return ageSeconds > 2;
  };

  const accent = tenant?.color || '#4f46e5';

  if (loading) {
    return <div style={styles.loading}>Loading jobs...</div>;
  }

  if (error) {
    return <div style={styles.error}>{error}</div>;
  }

  return (
    <div style={{ ...styles.container, borderTop: `4px solid ${accent}` }}>
      <div style={styles.header}>
        <div style={styles.titleRow}>
          <h2 style={styles.heading}>Jobs</h2>
          <span style={{ ...styles.tenantChip, backgroundColor: accent }}>
            {tenant?.label || tenantId}
          </span>
        </div>
        <span style={styles.count}>{jobs.length} jobs</span>
      </div>

      {jobs.length === 0 ? (
        <div style={styles.empty}>
          No jobs yet for {tenant?.label || tenantId}. Submit a job to get started.
        </div>
      ) : (
        <div style={styles.table}>
          {jobs.map((job) => (
            <div key={job.id} style={styles.row}>
              <div style={styles.cell}>
                <JobStatusBadge
                  status={job.status}
                  isCapHit={isCapHit(job)}
                />
              </div>
              <div style={styles.cell}>
                <div style={styles.cellLabel}>Job ID:</div>
                <div style={styles.cellValue}>{job.id.substring(0, 8)}...</div>
              </div>
              <div style={styles.cell}>
                <div style={styles.cellLabel}>Target:</div>
                <div style={styles.cellValue}>{job.targetId}</div>
              </div>
              <div style={styles.cell}>
                <div style={styles.cellLabel}>Payload:</div>
                <div style={styles.cellValue}>{job.payload || '(empty)'}</div>
              </div>
              <div style={styles.cell}>
                <div style={styles.cellLabel}>Idempotency Key:</div>
                <div style={styles.cellValue} title={job.idempotencyKey}>
                  {job.idempotencyKey || '(none)'}
                </div>
              </div>
              <div style={styles.cell}>
                <div style={styles.cellLabel}>Retries:</div>
                <div style={styles.cellValue}>
                  {job.retryCount} / {job.maxRetries}
                </div>
              </div>
              <div style={styles.cell}>
                <div style={styles.cellLabel}>Created:</div>
                <div style={styles.cellValue}>
                  {new Date(job.createdAt).toLocaleTimeString()}
                </div>
              </div>
              {job.errorMessage && (
                <div style={styles.cellError}>
                  Error: {job.errorMessage}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

const styles = {
  container: {
    backgroundColor: 'white',
    padding: '24px',
    borderRadius: '12px',
    boxShadow: '0 4px 16px rgba(15, 23, 42, 0.06)',
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '20px',
  },
  titleRow: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
  },
  heading: {
    margin: 0,
    fontSize: '18px',
    fontWeight: 700,
  },
  tenantChip: {
    color: 'white',
    padding: '4px 12px',
    borderRadius: '999px',
    fontSize: '12px',
    fontWeight: 700,
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
  },
  count: {
    color: '#64748b',
    fontSize: '13px',
    fontWeight: 500,
  },
  loading: {
    textAlign: 'center',
    padding: '40px',
    color: '#64748b',
  },
  error: {
    color: '#b91c1c',
    padding: '20px',
    backgroundColor: '#fee2e2',
    borderRadius: '12px',
    border: '1px solid #fecaca',
  },
  empty: {
    textAlign: 'center',
    padding: '40px',
    color: '#94a3b8',
    fontStyle: 'italic',
  },
  table: {
    display: 'flex',
    flexDirection: 'column',
    gap: '10px',
  },
  row: {
    display: 'grid',
    gridTemplateColumns: 'auto 1fr 1fr 2fr 1.5fr 1fr 1fr',
    gap: '12px',
    padding: '16px',
    backgroundColor: '#f8fafc',
    border: '1px solid #eef2f7',
    borderRadius: '10px',
    alignItems: 'center',
  },
  cell: {
    display: 'flex',
    flexDirection: 'column',
  },
  cellLabel: {
    fontSize: '11px',
    color: '#94a3b8',
    marginBottom: '2px',
    textTransform: 'uppercase',
    letterSpacing: '0.3px',
  },
  cellValue: {
    fontSize: '14px',
    fontWeight: 500,
    color: '#1e293b',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  cellError: {
    gridColumn: '1 / -1',
    color: '#b91c1c',
    fontSize: '12px',
    marginTop: '5px',
    padding: '8px 10px',
    backgroundColor: '#fee2e2',
    borderRadius: '8px',
  },
};

export default JobList;
