import axios from 'axios';

const API_BASE_URL = 'http://localhost:8080';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

export const jobApi = {
  createJob: (tenantId, jobRequest) => {
    return api.post('/jobs', jobRequest, {
      headers: { 'X-Tenant-Id': tenantId },
    });
  },

  getJobs: (tenantId) => {
    return api.get('/jobs', {
      headers: { 'X-Tenant-Id': tenantId },
    });
  },


  getSSEUrl: () => {
    return `${API_BASE_URL}/jobs/stream`;
  },
};

export const configApi = {
  getConcurrencyCaps: () => {
    return api.get('/config/concurrency');
  },
};

