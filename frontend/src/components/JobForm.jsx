import { useState } from 'react';
import { jobApi } from '../services/api';

function JobForm({ tenantId, tenant }) {
  const [payload, setPayload] = useState('');
  const [targetId, setTargetId] = useState('target-1');
  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const generateIdempotencyKey = () => {
    return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setIsSubmitting(true);
    setError(null);

    try {
      const jobRequest = {
        tenantId,
        targetId,
        payload,
        idempotencyKey: idempotencyKey.trim() || generateIdempotencyKey(),
      };

      const response = await jobApi.createJob(tenantId, jobRequest);
      console.log('Job created:', response.data);

      // Clear form
      setPayload('');
      setIdempotencyKey('');
    } catch (err) {
      console.error('Error creating job:', err);
      setError(err.response?.data?.message || 'Failed to create job');
    } finally {
      setIsSubmitting(false);
    }
  };

  const accent = tenant?.color || '#4f46e5';

  return (
    <div style={{ ...styles.container, borderTop: `4px solid ${accent}` }}>
      <div style={styles.headerRow}>
        <h2 style={styles.heading}>Submit New Job</h2>
        <span style={{ ...styles.tenantChip, backgroundColor: accent }}>
          {tenant?.label || tenantId}
        </span>
      </div>
      <p style={styles.subtitle}>
        This job will be created for{' '}
        <strong style={{ color: accent }}>{tenant?.label || tenantId}</strong>.
      </p>
      <form onSubmit={handleSubmit} style={styles.form}>
        <div style={styles.field}>
          <label htmlFor="targetId" style={styles.label}>
            Target ID:
          </label>
          <select
            id="targetId"
            value={targetId}
            onChange={(e) => setTargetId(e.target.value)}
            style={styles.select}
          >
            <option value="target-1">Target 1</option>
            <option value="target-2">Target 2</option>
            <option value="target-3">Target 3</option>
            <option value="target-4">Target 4</option>
            <option value="target-5">Target 5</option>
          </select>
        </div>

        <div style={styles.field}>
          <label htmlFor="payload" style={styles.label}>
            Payload:
          </label>
          <textarea
            id="payload"
            value={payload}
            onChange={(e) => setPayload(e.target.value)}
            placeholder="Enter job payload..."
            rows={4}
            style={styles.textarea}
          />
        </div>

        <div style={styles.field}>
          <label htmlFor="idempotencyKey" style={styles.label}>
            Idempotency Key (optional):
          </label>
          <input
            id="idempotencyKey"
            type="text"
            value={idempotencyKey}
            onChange={(e) => setIdempotencyKey(e.target.value)}
            placeholder="Auto-generated if empty"
            style={styles.input}
          />
          <span style={styles.hint}>
            Use the same key to prevent duplicate job execution
          </span>
        </div>

        {error && <div style={styles.error}>{error}</div>}

        <button
          type="submit"
          disabled={isSubmitting}
          style={{
            ...styles.button,
            backgroundColor: accent,
            opacity: isSubmitting ? 0.7 : 1,
            cursor: isSubmitting ? 'not-allowed' : 'pointer',
          }}
        >
          {isSubmitting ? 'Submitting…' : `Submit Job for ${tenant?.label || tenantId}`}
        </button>
      </form>
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
  headerRow: {
    display: 'flex',
    justifyContent: 'space-between',
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
  subtitle: {
    margin: '6px 0 18px',
    fontSize: '13px',
    color: '#64748b',
  },
  form: {
    display: 'flex',
    flexDirection: 'column',
    gap: '15px',
  },
  field: {
    display: 'flex',
    flexDirection: 'column',
  },
  label: {
    fontWeight: 600,
    fontSize: '13px',
    marginBottom: '6px',
    color: '#334155',
  },
  select: {
    padding: '10px',
    borderRadius: '8px',
    border: '1px solid #cbd5e1',
    fontSize: '14px',
    backgroundColor: '#f8fafc',
  },
  textarea: {
    padding: '10px',
    borderRadius: '8px',
    border: '1px solid #cbd5e1',
    fontSize: '14px',
    fontFamily: 'inherit',
    resize: 'vertical',
    backgroundColor: '#f8fafc',
  },
  input: {
    padding: '10px',
    borderRadius: '8px',
    border: '1px solid #cbd5e1',
    fontSize: '14px',
    backgroundColor: '#f8fafc',
  },
  hint: {
    fontSize: '12px',
    color: '#94a3b8',
    marginTop: '6px',
  },
  button: {
    padding: '12px 20px',
    color: 'white',
    border: 'none',
    borderRadius: '8px',
    fontSize: '15px',
    fontWeight: 600,
    marginTop: '4px',
    transition: 'opacity 0.15s ease',
  },
  error: {
    color: '#b91c1c',
    padding: '10px 12px',
    backgroundColor: '#fee2e2',
    borderRadius: '8px',
    border: '1px solid #fecaca',
    fontSize: '14px',
  },
};

export default JobForm;
