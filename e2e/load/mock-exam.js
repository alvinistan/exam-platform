// Load test of mock exams (Phase 14), run with k6 against the full Docker stack with the fake Google sign-in
// (docker-compose.e2e.yml). Each virtual student signs in through the real flow, then repeatedly starts a mock exam,
// answers every question with a few seconds between answers, and submits it.
//
//   k6 run -e STUDENTS=20 -e DURATION=3m e2e/load/mock-exam.js
//
// Setup signs in an admin and gives each student a manual plan, so students can take more than the one free mock.
// The thresholds below fail the run; results are summarised in docs/testing/test-report.md.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8088';
const STUDENTS = Number(__ENV.STUDENTS || 20);
const DURATION = __ENV.DURATION || '3m';
const QUESTIONS = Number(__ENV.QUESTIONS || 10);
// Seconds a student spends on a question; real students take far longer, so this is a heavy load per student.
const THINK = Number(__ENV.THINK_SECONDS || 2);
const SAMPLE_TITLE = 'O/L Science Revision (sample)';
const RUN = Date.now().toString(36);

const startTime = new Trend('mock_start_ms', true);
const answerTime = new Trend('mock_answer_ms', true);
const submitTime = new Trend('mock_submit_ms', true);

export const options = {
  setupTimeout: '5m',
  scenarios: {
    mock_exams: { executor: 'constant-vus', vus: STUDENTS, duration: DURATION, gracefulStop: '60s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    mock_start_ms: ['p(95)<1500'],
    mock_answer_ms: ['p(95)<500'],
    mock_submit_ms: ['p(95)<1500'],
  },
};

function param(url, name) {
  const m = new RegExp('[?&]' + name + '=([^&]*)').exec(url);
  return m ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : '';
}

/** The browser sign-in, step by step; cookies are passed by hand because they are Secure and this is plain http. */
function signIn(email, name) {
  const start = http.get(`${BASE}/api/auth/google/start?returnTo=%2F`, { redirects: 0, tags: { name: 'signin' } });
  if (start.status !== 302 || !start.cookies['__Host-oauth']) fail(`sign-in start: ${start.status}`);
  const authorize = start.headers['Location'];
  const login = start.cookies['__Host-oauth'][0].value;
  const google = http.post(
    authorize.split('?')[0],
    {
      redirect_uri: param(authorize, 'redirect_uri'),
      state: param(authorize, 'state'),
      nonce: param(authorize, 'nonce'),
      code_challenge: param(authorize, 'code_challenge'),
      client_id: param(authorize, 'client_id'),
      email,
      name,
    },
    { redirects: 0, tags: { name: 'signin' } },
  );
  const callback = http.get(google.headers['Location'], {
    redirects: 0,
    headers: { Cookie: `__Host-oauth=${login}` },
    tags: { name: 'signin' },
  });
  if (!callback.cookies['__Host-rt']) fail(`sign-in callback: ${callback.status} ${callback.headers['Location']}`);
  const token = http.post(`${BASE}/api/auth/token`, null, {
    headers: { Cookie: `__Host-rt=${callback.cookies['__Host-rt'][0].value}`, 'X-Requested-With': 'fetch' },
    tags: { name: 'signin' },
  });
  if (token.status !== 200) fail(`token: ${token.status}`);
  return token.json('accessToken');
}

function call(method, path, token, body, name) {
  return http.request(method, `${BASE}/api${path}`, body === undefined ? null : JSON.stringify(body), {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    tags: { name: name || path },
  });
}

export function setup() {
  const page = http.get(`${BASE}/api/exams?size=100`);
  const exams = page.json('items');
  const exam = (exams || []).find((e) => e.title === SAMPLE_TITLE);
  if (!exam) fail(`"${SAMPLE_TITLE}" isn't published; run the Playwright setup first`);

  const admin = signIn('admin@e2e.test', 'E2E Admin');
  const endsAt = new Date(Date.now() + 2 * 86_400_000).toISOString();
  const tokens = [];
  for (let i = 0; i < STUDENTS; i++) {
    const token = signIn(`load-${RUN}-${i}@e2e.test`, `Load Student ${i}`);
    const me = call('GET', '/users/me', token, undefined, 'me');
    const grant = call(
      'POST',
      '/admin/billing/entitlements',
      admin,
      {
        studentId: me.json('id'),
        endsAt,
        reason: 'Load test (Phase 14)',
      },
      'grant',
    );
    if (grant.status !== 201) fail(`grant: ${grant.status} ${grant.body}`);
    tokens.push(token);
    sleep(1.1); // stays inside the gateway's sign-in allowance (60 a minute per address)
  }
  return { examId: exam.id, tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const started = call(
    'POST',
    '/attempts',
    token,
    {
      examId: data.examId,
      mode: 'MOCK',
      topicIds: [],
      sizeBy: 'COUNT',
      questionCount: QUESTIONS,
    },
    'start',
  );
  startTime.add(started.timings.duration);
  if (!check(started, { 'mock started': (r) => r.status === 201 || r.status === 200 })) {
    sleep(5);
    return;
  }
  const attempt = started.json();
  for (const q of attempt.questions) {
    sleep(THINK * (0.5 + Math.random()));
    const label = q.options[Math.floor(Math.random() * q.options.length)].label;
    const saved = call(
      'PUT',
      `/attempts/${attempt.id}/answers/${q.position}`,
      token,
      {
        selected: [label],
        timeSpentMs: THINK * 1000,
      },
      'answer',
    );
    answerTime.add(saved.timings.duration);
    check(saved, { 'answer saved': (r) => r.status === 200 });
  }
  const submitted = call('POST', `/attempts/${attempt.id}/submit`, token, undefined, 'submit');
  submitTime.add(submitted.timings.duration);
  check(submitted, {
    'submitted and marked': (r) => r.status === 200 && r.json('status') === 'SCORED' && r.json('result') !== null,
  });
  sleep(1);
}
