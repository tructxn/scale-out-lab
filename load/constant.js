// Open model: requests arrive at RATE/s regardless of how fast the server answers.
import http from 'k6/http';

export const options = {
  scenarios: {
    constant: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 600),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  summaryTrendStats: ['p(50)', 'p(99)', 'max'],
};

export default function () {
  http.get(__ENV.TARGET || 'http://caller:8080/work');
}
