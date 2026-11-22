import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 1,
    iterations: 100
}

const BASE_URL = 'http://app:8080';
const SHORT_KEY = 'BROtxn';
export default function () {
    const res = http.get(`${BASE_URL}/s/public/${SHORT_KEY}`, {
        redirects: 0,
    })

    check(res, {
        'status is 302 or 429': (r) => r.status === 302 || r.status === 429,
    })

    sleep(0.1);
}



