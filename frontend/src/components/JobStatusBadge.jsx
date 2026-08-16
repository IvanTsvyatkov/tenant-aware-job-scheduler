function JobStatusBadge({ status, isCapHit }) {
  const getStatusConfig = () => {
    if (status === 'PENDING' && isCapHit) {
      return {
        label: 'Waiting (Cap Limit)',
        icon: '⏸',
        color: '#fd7e14',
        bgColor: '#fff3cd',
      };
    }

    switch (status) {
      case 'PENDING':
        return {
          label: 'Pending',
          icon: '⏳',
          color: '#6c757d',
          bgColor: '#e9ecef',
        };
      case 'RUNNING':
        return {
          label: 'Running',
          icon: '▶',
          color: '#007bff',
          bgColor: '#cfe2ff',
        };
      case 'SUCCEEDED':
        return {
          label: 'Succeeded',
          icon: '✓',
          color: '#28a745',
          bgColor: '#d4edda',
        };
      case 'FAILED':
        return {
          label: 'Failed',
          icon: '✗',
          color: '#dc3545',
          bgColor: '#f8d7da',
        };
      default:
        return {
          label: status,
          icon: '?',
          color: '#6c757d',
          bgColor: '#e9ecef',
        };
    }
  };

  const config = getStatusConfig();

  return (
    <div
      style={{
        ...styles.badge,
        color: config.color,
        backgroundColor: config.bgColor,
        borderColor: config.color,
      }}
    >
      <span style={styles.icon}>{config.icon}</span>
      <span style={styles.label}>{config.label}</span>
    </div>
  );
}

const styles = {
  badge: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: '5px',
    padding: '6px 12px',
    borderRadius: '16px',
    fontSize: '12px',
    fontWeight: '600',
    border: '1px solid',
    whiteSpace: 'nowrap',
  },
  icon: {
    fontSize: '14px',
  },
  label: {
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
  },
};

export default JobStatusBadge;
