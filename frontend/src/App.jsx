import { useState, useEffect } from 'react';
import JobForm from './components/JobForm';
import JobList from './components/JobList';
import { configApi } from './services/api';

// Central tenant definitions so the whole UI can share labels & accent colors
export const TENANTS = [
  { id: 'tenant1', label: 'Tenant 1', color: '#4f46e5' },
  { id: 'tenant2', label: 'Tenant 2', color: '#0d9488' },
  { id: 'tenant3', label: 'Tenant 3', color: '#db2777' },
];

export const getTenant = (id) => TENANTS.find((t) => t.id === id) || TENANTS[0];

function App() {
  const [tenantId, setTenantId] = useState('tenant1');
  const [caps, setCaps] = useState(null);
  const [capsError, setCapsError] = useState(false);

  const activeTenant = getTenant(tenantId);

  // Load concurrency caps from the backend so they stay in sync with application.yml
  useEffect(() => {
    configApi
      .getConcurrencyCaps()
      .then((res) => setCaps(res.data))
      .catch((err) => {
        console.error('Failed to load concurrency caps:', err);
        setCapsError(true);
      });
  }, []);

  return (
    <div style={styles.app}>
      <header style={styles.header}>
        <div style={styles.brand}>
          <span style={styles.logo}>⚙️</span>
          <h1 style={styles.title}>Tenant Job Scheduler</h1>
        </div>
        <div style={styles.tenantSelector}>
          <label htmlFor="tenant" style={styles.tenantLabel}>
            Active Tenant
          </label>
          <select
            id="tenant"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            style={{
              ...styles.tenantSelect,
              borderColor: activeTenant.color,
              color: activeTenant.color,
            }}
          >
            {TENANTS.map((t) => (
              <option key={t.id} value={t.id}>
                {t.label}
              </option>
            ))}
          </select>
        </div>
      </header>

      {/* Prominent banner making it explicit which tenant everything applies to */}
      <div
        style={{
          ...styles.tenantBanner,
          backgroundColor: `${activeTenant.color}14`,
          borderColor: activeTenant.color,
        }}
      >
        <span style={{ ...styles.tenantDot, backgroundColor: activeTenant.color }} />
        <span style={styles.bannerText}>
          You are viewing and creating jobs for{' '}
          <strong style={{ color: activeTenant.color }}>{activeTenant.label}</strong>. All
          data below is isolated to this tenant.
        </span>
      </div>

      <main style={styles.main}>
        <div style={styles.grid}>
          <div style={styles.column}>
            <JobForm tenantId={tenantId} tenant={activeTenant} />
          </div>
          <div style={styles.column}>
            <JobList tenantId={tenantId} tenant={activeTenant} />
          </div>
        </div>
      </main>

      <footer style={styles.footer}>
        <div style={styles.info}>
          <h3 style={styles.footerTitle}>Concurrency Caps</h3>
          <p style={styles.footerSubtitle}>
            Loaded live from the backend configuration
          </p>
          {capsError ? (
            <div style={styles.capsError}>Unable to load caps from backend.</div>
          ) : !caps ? (
            <div style={styles.capsLoading}>Loading caps…</div>
          ) : (
            <div style={styles.capsGrid}>
              <div style={styles.capCard}>
                <div style={styles.capValue}>{caps.globalMax}</div>
                <div style={styles.capLabel}>Global</div>
              </div>
              <div style={styles.capCard}>
                <div style={styles.capValue}>{caps.tenantMax}</div>
                <div style={styles.capLabel}>Per Tenant</div>
              </div>
              <div style={styles.capCard}>
                <div style={styles.capValue}>{caps.targetMax}</div>
                <div style={styles.capLabel}>Per Target</div>
              </div>
            </div>
          )}
        </div>
      </footer>
    </div>
  );
}

const styles = {
  app: {
    minHeight: '100vh',
    background: 'linear-gradient(180deg, #f1f5f9 0%, #e2e8f0 100%)',
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
    color: '#1e293b',
  },
  header: {
    background: 'linear-gradient(90deg, #1e293b 0%, #334155 100%)',
    color: 'white',
    padding: '18px 32px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
  },
  brand: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
  },
  logo: {
    fontSize: '26px',
  },
  title: {
    margin: 0,
    fontSize: '24px',
    fontWeight: 700,
    letterSpacing: '0.3px',
  },
  tenantSelector: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
  },
  tenantLabel: {
    fontSize: '13px',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    color: '#cbd5e1',
  },
  tenantSelect: {
    padding: '9px 14px',
    fontSize: '15px',
    fontWeight: 600,
    borderRadius: '8px',
    border: '2px solid',
    backgroundColor: 'white',
    cursor: 'pointer',
    outline: 'none',
  },
  tenantBanner: {
    maxWidth: '1200px',
    margin: '24px auto 0',
    padding: '14px 20px',
    borderRadius: '10px',
    border: '1px solid',
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
  },
  tenantDot: {
    width: '12px',
    height: '12px',
    borderRadius: '50%',
    flexShrink: 0,
  },
  bannerText: {
    fontSize: '15px',
    color: '#334155',
  },
  main: {
    maxWidth: '1200px',
    margin: '0 auto',
    padding: '24px 20px 40px',
  },
  grid: {
    display: 'grid',
    gridTemplateColumns: 'minmax(320px, 1fr) minmax(0, 1.8fr)',
    gap: '24px',
    alignItems: 'start',
  },
  column: {
    minWidth: 0,
  },
  footer: {
    backgroundColor: 'white',
    padding: '28px 20px',
    borderTop: '1px solid #e2e8f0',
  },
  info: {
    maxWidth: '1200px',
    margin: '0 auto',
  },
  footerTitle: {
    margin: 0,
    fontSize: '18px',
    fontWeight: 700,
  },
  footerSubtitle: {
    margin: '4px 0 16px',
    fontSize: '13px',
    color: '#64748b',
  },
  capsGrid: {
    display: 'flex',
    gap: '16px',
    flexWrap: 'wrap',
  },
  capCard: {
    minWidth: '120px',
    padding: '16px 20px',
    backgroundColor: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: '10px',
    textAlign: 'center',
  },
  capValue: {
    fontSize: '28px',
    fontWeight: 800,
    color: '#4f46e5',
  },
  capLabel: {
    marginTop: '4px',
    fontSize: '13px',
    color: '#64748b',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
  },
  capsLoading: {
    color: '#64748b',
    fontStyle: 'italic',
  },
  capsError: {
    color: '#dc2626',
  },
};

export default App;
