import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '5s', target: 20 },
        { duration: '15s', target: 50 },
        { duration: '5s', target: 0 },
    ],

    thresholds: {
        http_req_duration: ['p(95)<30'],
        http_req_failed: ['rate<0.01'],
    }
}

const BASE_URL = 'http://app:8080';
const SHORT_KEY = 'BROtxn';

export default function () {
    const params = {
        headers: {
            'X-Forwarded-For': `10.${__VU}.${__ITER % 200}.${Math.floor(Math.random() * 250) + 1}`
        },
        redirects: 0
    }
    const res = http.get(`${BASE_URL}/s/public/${SHORT_KEY}`, params)

    check(res, {
        'status is 302': (r) => r.status === 302,
    })

    sleep(0.1)
}
