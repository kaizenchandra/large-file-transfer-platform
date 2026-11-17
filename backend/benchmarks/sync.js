import http from 'k6/http';
import {check, sleep} from 'k6';

export const options = {vus: Number(__ENV.DEVICES || 1000), duration: '60s'};
let cursor = 0;
export default function () {
    const r = http.get(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/sync/changes?cursor=${cursor}`, {headers: {Authorization: __ENV.DRIVE_AUTH}});
    if (check(r, {'success': r => r.status === 200})) cursor = r.json('cursor');
    sleep(2);
}
