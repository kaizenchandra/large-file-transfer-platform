import http from 'k6/http';
import {check} from 'k6';

export const options = {
    scenarios: {
        metadata: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 1000),
            timeUnit: '1s',
            duration: __ENV.DURATION || '60s',
            preAllocatedVUs: 100,
            maxVUs: 2000
        }
    }, thresholds: {http_req_failed: ['rate<0.01'], http_req_duration: ['p(99)<50']}
};
export default function () {
    const r = http.get(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/files?limit=100`, {headers: {Authorization: __ENV.DRIVE_AUTH}});
    check(r, {'success': r => r.status === 200});
}
