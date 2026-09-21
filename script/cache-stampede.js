import http from 'k6/http';
import { check, sleep } from 'k6';

// k6 load test simulating a Cache Stampede scenario
// Command: k6 run --vus 50 --duration 10s script/cache-stampede.js

export const options = {
  scenarios: {
    stampede: {
      executor: 'per-vu-iterations',
      vus: 50,
      iterations: 1,
      maxDuration: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'], // Failure rate under 5%
    http_req_duration: ['p(95)<1000'], // 95% under 1s
  },
};

export function setup() {
  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
  const target = __ENV.TARGET || 'naive'; // 'naive' or 'mutex'

  console.log(`Setting up test against: ${baseUrl}/api/catalog/hot-deal/${target}`);

  // 1. Prime the cache
  http.get(`${baseUrl}/api/catalog/hot-deal/${target}`);

  // 2. Invalidate hot key
  http.post(`${baseUrl}/api/catalog/evict?key=catalog::hot-deal`);
  console.log('Evicted key catalog::hot-deal. Starting 50 concurrent VUs...');
}

export default function () {
  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
  const target = __ENV.TARGET || 'naive';

  const res = http.get(`${baseUrl}/api/catalog/hot-deal/${target}`, {
    timeout: '3s',
  });

  check(res, {
    'status is 200': (r) => r.status === 200,
    'has product name': (r) => r.body && r.body.includes('BTC Ultra Gaming Rig'),
  });
}
