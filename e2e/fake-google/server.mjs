// A stand-in for Google sign-in, used only by the end-to-end tests (Phase 14). It is never part of a real deployment:
// it runs only in infra/compose/docker-compose.e2e.yml, and identity-service talks to it only because that file points
// identity.google.* at it.
//
// /authorize  shows an account form (the test types an email address) and redirects back with a one-time code
// /token      checks the client secret and the PKCE verifier, then returns an RS256-signed ID token with the nonce
// /certs      the public key for that signature
//
// No dependencies: Node's built-in http and crypto only.
import { createHash, generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { createServer } from 'node:http';

const PORT = Number(process.env.PORT ?? 8099);
const CLIENT_ID = process.env.CLIENT_ID ?? 'e2e-client.apps.googleusercontent.com';
const CLIENT_SECRET = process.env.CLIENT_SECRET ?? 'e2e-client-secret';
const ISSUER = 'https://accounts.google.com';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const KID = 'e2e-' + randomBytes(4).toString('hex');
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: KID, alg: 'RS256', use: 'sig' };

/** code -> what the ID token will say; each code works once and for five minutes. */
const grants = new Map();

const b64url = (data) => Buffer.from(data).toString('base64url');
const escapeHtml = (s) =>
  String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

function idToken(grant) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT', kid: KID }));
  const claims = b64url(
    JSON.stringify({
      iss: ISSUER,
      aud: CLIENT_ID,
      sub: 'e2e-' + createHash('sha256').update(grant.email).digest('hex').slice(0, 20),
      email: grant.email,
      email_verified: true,
      name: grant.name,
      nonce: grant.nonce,
      iat: now,
      exp: now + 3600,
    }),
  );
  const signature = sign('RSA-SHA256', Buffer.from(`${header}.${claims}`), privateKey).toString('base64url');
  return `${header}.${claims}.${signature}`;
}

function send(res, status, type, body) {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(body);
}

const json = (res, status, value) => send(res, status, 'application/json', JSON.stringify(value));

async function readForm(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return new URLSearchParams(Buffer.concat(chunks).toString('utf8'));
}

function accountForm(params) {
  const hidden = ['redirect_uri', 'state', 'nonce', 'code_challenge', 'client_id']
    .map((k) => `<input type="hidden" name="${k}" value="${escapeHtml(params.get(k) ?? '')}">`)
    .join('');
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Test sign-in</title></head>
<body><main><h1>Test sign-in (not Google)</h1>
<form method="post" action="/authorize">${hidden}
<p><label>Email <input name="email" type="email" required></label></p>
<p><label>Name <input name="name" required></label></p>
<p><button type="submit">Continue</button> <button type="submit" name="cancel" value="1">Cancel</button></p>
</form></main></body></html>`;
}

createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  try {
    if (url.pathname === '/healthz') return send(res, 200, 'text/plain', 'ok');
    if (url.pathname === '/certs') return json(res, 200, { keys: [jwk] });

    if (url.pathname === '/authorize' && req.method === 'GET') {
      const p = url.searchParams;
      if (p.get('client_id') !== CLIENT_ID || p.get('response_type') !== 'code' || p.get('code_challenge_method') !== 'S256') {
        return send(res, 400, 'text/plain', 'bad authorization request');
      }
      return send(res, 200, 'text/html; charset=utf-8', accountForm(p));
    }

    if (url.pathname === '/authorize' && req.method === 'POST') {
      const f = await readForm(req);
      const back = new URL(f.get('redirect_uri'));
      back.searchParams.set('state', f.get('state') ?? '');
      if (f.get('cancel')) {
        back.searchParams.set('error', 'access_denied');
      } else {
        const code = randomBytes(24).toString('base64url');
        grants.set(code, {
          email: f.get('email'),
          name: f.get('name'),
          nonce: f.get('nonce'),
          challenge: f.get('code_challenge'),
          expires: Date.now() + 5 * 60_000,
        });
        back.searchParams.set('code', code);
      }
      res.writeHead(302, { Location: back.toString() });
      return res.end();
    }

    if (url.pathname === '/token' && req.method === 'POST') {
      const f = await readForm(req);
      let id = f.get('client_id');
      let secret = f.get('client_secret');
      const basic = req.headers.authorization?.match(/^Basic (.+)$/);
      if (basic) {
        [id, secret] = Buffer.from(basic[1], 'base64').toString('utf8').split(':').map(decodeURIComponent);
      }
      const grant = grants.get(f.get('code') ?? '');
      grants.delete(f.get('code') ?? '');
      if (id !== CLIENT_ID || secret !== CLIENT_SECRET || f.get('grant_type') !== 'authorization_code') {
        return json(res, 401, { error: 'invalid_client' });
      }
      if (!grant || grant.expires < Date.now()) return json(res, 400, { error: 'invalid_grant' });
      const challenge = createHash('sha256')
        .update(f.get('code_verifier') ?? '')
        .digest('base64url');
      if (challenge !== grant.challenge) {
        return json(res, 400, { error: 'invalid_grant', error_description: 'PKCE verification failed' });
      }
      return json(res, 200, { access_token: 'e2e', token_type: 'Bearer', expires_in: 3599, id_token: idToken(grant) });
    }

    send(res, 404, 'text/plain', 'not found');
  } catch (e) {
    console.error(e);
    send(res, 500, 'text/plain', 'error');
  }
}).listen(PORT, () => console.log(`Fake Google sign-in on :${PORT}`));
