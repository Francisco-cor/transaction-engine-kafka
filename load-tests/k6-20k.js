import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// 20k transactions with 90% hot-account, 80 rps, for F6 sharding validation
// Run: k6 run --env BASE_URL=http://localhost:8080 load-tests/k6-20k.js
// Expect p95 <500ms, lock wait p95 <30ms with optimistic + sharding (vs 42ms baseline)

export const options = {
  scenarios: {
    transactions20k: {
      executor: 'constant-arrival-rate',
      rate: 80,
      timeUnit: '1s',
      duration: '250s', // 80*250=20000
      preAllocatedVUs: 30,
      maxVUs: 80,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNTS = ['hot-account-001', 'hot-account-002', 'demo-acc-001', 'demo-acc-002', 'acc-cold-001'];

function pickAccount() {
  const r = Math.random();
  if (r < 0.9) return 'hot-account-001';
  if (r < 0.93) return 'hot-account-002';
  if (r < 0.95) return 'demo-acc-001';
  return ACCOUNTS[Math.floor(Math.random() * ACCOUNTS.length)];
}

export default function () {
  const accountId = pickAccount();
  const amount = (Math.random() * 50 + 5).toFixed(4);
  const type = Math.random() < 0.7 ? 'DEBIT' : 'CREDIT';
  const idempotencyKey = uuidv();
  const payload = JSON.stringify({
    accountId: accountId,
    amount: parseFloat(amount),
    type: type,
    currency: 'MXN',
    customerNote: Math.random() < 0.05 ? 'k6 20k hot 90%' : undefined,
  });

  const headers = {
    'Content-Type': 'application/json',
    'Idempotency-Key': idempotencyKey,
    'X-Tenant-Id': 'demo',
    'X-Correlation-Id': uuidv(),
  };

  const res = http.post(`${BASE_URL}/transactions`, payload, { headers });

  check(res, {
    '202 or 409': (r) => r.status === 202 || r.status === 409,
    'no 5xx': (r) => r.status < 500,
  });

  sleep(0.01);
}

export function handleSummary(data) {
  return {
    'reports/load/k6-20k-summary.json': JSON.stringify(data, null, 2),
  };
}
