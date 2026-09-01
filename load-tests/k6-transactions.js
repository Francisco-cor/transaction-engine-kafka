import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// 10k transactions with hot keys, 50 rps, 20 VUs, 5% body mismatch 409
// Run: k6 run --env BASE_URL=http://localhost:8080 load-tests/k6-transactions.js
// Or via: powershell -File scripts/Invoke-Project.ps1 -Command load

export const options = {
  scenarios: {
    transactions: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '200s', // 50*200=10000
      preAllocatedVUs: 20,
      maxVUs: 50,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNTS = ['demo-acc-001', 'demo-acc-002', 'hot-account-001', 'hot-account-002', 'hot-account-003'];
// Zipf-like hot: 70% to hot-account-001, 20% to demo-acc-001, rest random
function pickAccount() {
  const r = Math.random();
  if (r < 0.5) return 'hot-account-001';
  if (r < 0.7) return 'hot-account-002';
  if (r < 0.8) return 'demo-acc-001';
  return ACCOUNTS[Math.floor(Math.random() * ACCOUNTS.length)];
}

export default function () {
  const accountId = pickAccount();
  const amount = (Math.random() * 90 + 10).toFixed(4); // 10.00..99.99
  const type = Math.random() < 0.7 ? 'DEBIT' : 'CREDIT';
  const idempotencyKey = uuidv();
  const payload = JSON.stringify({
    accountId: accountId,
    amount: parseFloat(amount),
    type: type,
    currency: 'MXN',
    customerNote: Math.random() < 0.1 ? 'k6 load' : undefined,
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

  // 5% exercise body mismatch: reuse same key with different amount should 409
  if (Math.random() < 0.05) {
    const payload2 = JSON.stringify({
      accountId: accountId,
      amount: (parseFloat(amount) + 1).toFixed(4) * 1,
      type: type,
      currency: 'MXN',
    });
    const res2 = http.post(`${BASE_URL}/transactions`, payload2, { headers });
    check(res2, {
      '409 on mismatch': (r) => r.status === 409,
    });
  }

  sleep(0.02);
}

export function handleSummary(data) {
  return {
    'reports/load/k6-summary.json': JSON.stringify(data, null, 2),
  };
}
