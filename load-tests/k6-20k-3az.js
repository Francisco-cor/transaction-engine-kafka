import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// 20k 3AZ — 50rps 400s =20k distributed across 3 AZs (account shards) for F11
// Run: k6 run --env BASE_URL=http://localhost:8080 load-tests/k6-20k-3az.js

export const options = {
  scenarios: {
    az_a: {
      executor: 'constant-arrival-rate',
      rate: 17,
      timeUnit: '1s',
      duration: '400s',
      preAllocatedVUs: 10,
      maxVUs: 30,
      env: { AZ: 'a' },
    },
    az_b: {
      executor: 'constant-arrival-rate',
      rate: 17,
      timeUnit: '1s',
      duration: '400s',
      preAllocatedVUs: 10,
      maxVUs: 30,
      env: { AZ: 'b' },
    },
    az_c: {
      executor: 'constant-arrival-rate',
      rate: 16,
      timeUnit: '1s',
      duration: '400s',
      preAllocatedVUs: 10,
      maxVUs: 30,
      env: { AZ: 'c' },
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AZ = __ENV.AZ || 'a';
// 3AZ accounts: each AZ has hot account shard
const ACCOUNTS_A = ['hot-account-001-a', 'demo-acc-001-a', 'acc-a-001'];
const ACCOUNTS_B = ['hot-account-001-b', 'demo-acc-002-b', 'acc-b-001'];
const ACCOUNTS_C = ['hot-account-001-c', 'acc-c-001', 'acc-c-002'];

function pickAccount() {
  if (AZ === 'a') return Math.random() < 0.85 ? 'hot-account-001-a' : ACCOUNTS_A[Math.floor(Math.random()*ACCOUNTS_A.length)];
  if (AZ === 'b') return Math.random() < 0.85 ? 'hot-account-001-b' : ACCOUNTS_B[Math.floor(Math.random()*ACCOUNTS_B.length)];
  return Math.random() < 0.85 ? 'hot-account-001-c' : ACCOUNTS_C[Math.floor(Math.random()*ACCOUNTS_C.length)];
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
    az: AZ,
    customerNote: `3az ${AZ}`,
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
  sleep(0.02);
}

export function handleSummary(data) {
  return {
    'reports/load/k6-20k-3az-summary.json': JSON.stringify(data, null, 2),
  };
}
