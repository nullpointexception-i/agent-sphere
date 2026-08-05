#!/usr/bin/env node
/**
 * Local mock OIDC IdP — simulates the "bole" third-party identity provider for
 * locally testing the AgentSphere SSO login chain (widget + main login page).
 *
 * Implements the parts of the OIDC Authorization Code + PKCE (S256) flow that the
 * AgentSphere backend (SsoOidcClient) requires:
 *   - GET  /.well-known/openid-configuration
 *   - GET  /authorize  | /oauth2/authorize   -> auto-redirects back with ?code=&state=
 *   - POST /token      | /oauth2/token       -> validates PKCE, returns RS256 id_token
 *   - GET  /jwks                            -> RSA public key (JWKS)
 *
 * Dev-friendly: client_id/client_secret are NOT strictly validated (any non-empty
 * value passes). PKCE (S256), redirect_uri and nonce checks are always enforced.
 * The issued id_token `aud` is set to the requesting client_id so it matches the
 * agent_identity_provider row.
 *
 * Zero dependencies (Node >= 20). Run:
 *   node mock-oidc-server.mjs            # listens on :9000
 *   MOCK_IDP_CLIENT_ID=x node ...        # optional; used only for the startup log
 *
 * Backend identity provider row (agent_identity_provider) should point here:
 *   code=bole, issuer=http://localhost:9000,
 *   client_id=<any non-empty>, client_secret=<any non-empty>,
 *   authorization_endpoint=http://localhost:9000/oauth2/authorize
 *   token_endpoint=http://localhost:9000/oauth2/token
 *   jwks_url=http://localhost:9000/jwks
 *   scopes='openid email profile', enabled=TRUE
 * (Insert the row via direct SQL — the backend falls back to plaintext client_secret.)
 */
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import {
  createHash,
  createPrivateKey,
  createPublicKey,
  createSign,
} from 'node:crypto';

const PORT = Number(process.env.MOCK_IDP_PORT || 9000);
const ISSUER = process.env.MOCK_IDP_ISSUER || `http://localhost:${PORT}`;
const CLIENT_ID = process.env.MOCK_IDP_CLIENT_ID || 'local-client';
const CLIENT_SECRET = process.env.MOCK_IDP_CLIENT_SECRET || 'local-secret';
const SUBJECT = process.env.MOCK_IDP_SUBJECT || 'test-subject-001';
const USER_EMAIL = 'bole@test.local';
const USER_NAME = 'Bole 测试用户';
const KID = 'test-key';

/**
 * 内置固定测试 RSA-2048 密钥（仅本地开发测试用，勿用于生产）。
 * 固定密钥保证每次启动后端 `RemoteJWKSet` 缓存的 JWKS 依然有效，
 * 避免 mock 重启换 key 导致 id_token 验签失败（S0006）。
 */
const PRIVATE_KEY_PEM = `-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCk8q0ghfUdcFLm
U+uvZ5+SN3efqjYTK3fz8fre6c5KXIO29nUr/7MA2kfzNKtEgNQ84/K0G4/Mcfgt
P4GpvPLK1LhniQazNjyi+yYN0llLtmaadb7dP9ijKqMlxsHeamfvLeC2j5Q5jg7o
6z6cTm4ZzTSYx5X09f0aZ22y1pgAqTyqbQy2JWEa17Iqy3X4gmPIWOmIvEd5hHon
251sUWbs5guWnaH3pnZ4Bwa4GnlHIkoLgGfK3kIGieo8yYtc9x3cm0h+ZTcHrWFh
7x8r0xJRS+FJOaunjGX27JpZBhagXjQ4+zxA7kUlXakQRlmO5E4kDnQp3zYELiQK
H2Ls4PbVAgMBAAECggEALrXmXyJwQnDvmPhE8vw2TRLWFmn+PDmAE53//CZb2+UN
C8AJeHdFusUVwQK2SYTuFXw729M+SpgvvqiQUIAIhXXt7qv4MMH4M/NJWHqr/Ovf
bHhRn5gYAkTtxKHfftvFKQ9l5m0MfawD/uO3bE4tkbLx5R4IEkGi9lo5z35Dt1PA
eRUDnULMXvYLiFndO+j/I+ap1nnqvdUVHIhRI6+kGrxknCiqd8NAb7hTXcrQ7Wwd
J3QgR0/ElBwbQOtfu473BU/83H1E9C7T9sl4blyWqTSlz4r/y3lH15tsN6f6hKJ0
i92sfLzNSowdZ5vYBdHns3Y0kujziOqJFrEzahRPlQKBgQDnnKefObDCtFVq/011
bfreI4iajhyJcG37pQct4taOK+gfFZl8t0/hwu4KdU6x5OfXx8nIsb+2RzZhx/FE
AYg2EHKVW93cVwXlm7AkZ24T72LzQz/LvgOBapeBiPKDvzTkLo4Uz1oHbgI/wgYn
NXTYv8t5sg7cjLD9rqMDEcHjOwKBgQC2UQX1oKwoaUMN0srTZaCua3mzV1JwSHWP
6RBcVgEXX2vQjM5eUwhhfcKNTTU1hjdEDBtD3A2mYo2gsR2DzX+ruLIclQ6m6Jrm
x1DhhtiZf0JJPUrB0kNzPz+tcL6WY3VbCzWoFFQkA3O+jjITrS3HZBjvK1Vijwiz
vbxEHNbNLwKBgQCsMn85VXYCvHqJS3j2ZqdOgtKsPh4fXPSYVHDK/8yO9Tvl6HQu
EkgUzI2YPvEcN9jbSBHQ4b5sMfPPrQzGh/ESaRYXz65ahGTA+ghyeGeR8Lf7rrL+
sq3+iRZNW1ka9IzJXYeLF0KTJYoMhx92BTtzbt1EBEsiVIO+iBlwTcJ+QwKBgC1M
3Io3rg8seHjK05LLQa2VDtw86kLz/iIP6vzGceb40rUzB2PwhbiTou+xK3NtMfY6
e9vUpZ+eBUrUN100hnCIp9jqQIXAbhzBkZs4AkHBmRrRm+2k7RWJtazGwtRjShmh
I2fsBSwdn3jNpCu3cBSHDpr+zWx71dGzZ0AVWloJAoGAc5mZFDlkPCMGgS4Jti+w
idqTUvNk2e156bB4flb9A/X/ydHhoX+Elgc9dhd4KMfT435BXh0KM/rEuZTuT36p
czXp8LRK90sjS/qY463ICtCeGmGlJLQFL9Mh/opB0w+Wf0Q85rRtAhu4QhYq1Msw
uT6BkVdJS1bCwvWpMlxShgY=
-----END PRIVATE KEY-----
`;

const privateKey = createPrivateKey(PRIVATE_KEY_PEM);
const publicJwk = createPublicKey(privateKey).export({ format: 'jwk' });

const authCodes = new Map(); // code -> { challenge, nonce, state, redirectUri }

function base64url(input) {
  return Buffer.from(input).toString('base64url');
}

function signJwt(payload) {
  const header = { alg: 'RS256', typ: 'JWT', kid: KID };
  const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(payload))}`;
  const signature = createSign('RSA-SHA256').update(signingInput).end().sign(privateKey);
  return `${signingInput}.${base64url(signature)}`;
}

function issuerPayload(extra = {}) {
  const now = Math.floor(Date.now() / 1000);
  return {
    iss: ISSUER,
    sub: SUBJECT,
    aud: CLIENT_ID,
    iat: now,
    exp: now + 3600,
    email: USER_EMAIL,
    name: USER_NAME,
    ...extra,
  };
}function readForm(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => resolve(new URLSearchParams(body)));
    req.on('error', reject);
  });
}

function sendJson(res, status, obj) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

function redirect(res, location) {
  res.writeHead(302, { Location: location });
  res.end();
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;
  const isAuthorize = path === '/authorize' || path === '/oauth2/authorize';
  const isToken = path === '/token' || path === '/oauth2/token';
  const isJwks = path === '/jwks' || path === '/oauth2/jwks';
  console.log(`[mock-idp] ${req.method} ${path}${url.search}`);

  try {
    if (req.method === 'GET' && path === '/.well-known/openid-configuration') {
      return sendJson(res, 200, {
        issuer: ISSUER,
        authorization_endpoint: `${ISSUER}/oauth2/authorize`,
        token_endpoint: `${ISSUER}/oauth2/token`,
        jwks_uri: `${ISSUER}/jwks`,
        response_types_supported: ['code'],
        code_challenge_methods_supported: ['S256'],
        id_token_signing_alg_values_supported: ['RS256'],
        scopes_supported: ['openid', 'email', 'profile'],
        subject_types_supported: ['public'],
      });
    }

    if (req.method === 'GET' && isJwks) {
      return sendJson(res, 200, {
        keys: [{ kty: 'RSA', use: 'sig', alg: 'RS256', kid: KID, n: publicJwk.n, e: publicJwk.e }],
      });
    }

    if (req.method === 'GET' && isAuthorize) {
      const clientId = url.searchParams.get('client_id');
      const redirectUri = url.searchParams.get('redirect_uri');
      const state = url.searchParams.get('state');
      const nonce = url.searchParams.get('nonce');
      const challenge = url.searchParams.get('code_challenge');
      const challengeMethod = url.searchParams.get('code_challenge_method') || 'plain';
      if (!clientId || !redirectUri || !state || !nonce || !challenge) {
        return sendJson(res, 400, { error: 'invalid_request', error_description: 'missing/invalid authorize params' });
      }
      const code = randomUUID().replace(/-/g, '');
      authCodes.set(code, { challenge, challengeMethod, nonce, state, redirectUri });
      const sep = redirectUri.includes('?') ? '&' : '?';
      return redirect(res, `${redirectUri}${sep}code=${encodeURIComponent(code)}&state=${encodeURIComponent(state)}`);
    }

    if (req.method === 'POST' && isToken) {
      const form = await readForm(req);
      const { grant_type: grantType, code, redirect_uri: redirectUri, client_id: clientId, client_secret: clientSecret, code_verifier: verifier } = Object.fromEntries(form);
      const entry = authCodes.get(code);
      if (grantType !== 'authorization_code' || !entry) {
        return sendJson(res, 400, { error: 'invalid_grant' });
      }
      // Dev-friendly: any non-empty client credentials are accepted.
      if (!clientId || !clientSecret) {
        return sendJson(res, 401, { error: 'invalid_client' });
      }
      if (redirectUri !== entry.redirectUri) {
        return sendJson(res, 400, { error: 'invalid_grant', error_description: 'redirect_uri mismatch' });
      }
      const digest = createHash('sha256').update(verifier || '').digest('base64url');
      if (digest !== entry.challenge) {
        return sendJson(res, 400, { error: 'invalid_grant', error_description: 'PKCE code_verifier mismatch' });
      }
      authCodes.delete(code);
      // aud must match the backend provider's client_id — use the requesting client_id.
      const idToken = signJwt(issuerPayload({ aud: clientId, nonce: entry.nonce }));
      return sendJson(res, 200, {
        access_token: randomUUID().replace(/-/g, ''),
        token_type: 'Bearer',
        expires_in: 3600,
        id_token: idToken,
      });
    }

    return sendJson(res, 404, { error: 'not_found' });
  } catch (err) {
    console.error('[mock-idp] error', err);
    return sendJson(res, 500, { error: 'internal_error' });
  }
});

server.listen(PORT, () => {
  console.log(`[mock-idp] OIDC mock listening on :${PORT}`);
  console.log(`[mock-idp] issuer=${ISSUER}`);
  console.log(`[mock-idp] client_id=${CLIENT_ID} client_secret=${CLIENT_SECRET} subject=${SUBJECT}`);
});
