Git-over-HTTP with Keycloak (experimental)
==========================================

> **Experimental.** Git-over-HTTP support with Keycloak is opt-in.
> Bearer tokens are validated locally against the realm's JWKS — see
> *How authentication works on the wire* below. The "experimental"
> label will be removed after a release cycle of production exposure.
> Until then, assume operational details (log lines, JWKS cache
> cooldown, claim handling) may change between minor versions.

How to push and fetch over HTTPS against a Gerrit instance whose OAuth provider
is Keycloak, **without** using the deprecated Resource Owner Password
Credentials (RoPC / Direct Access Grants) flow.

The plugin-side configuration is documented in [config.md](config.md) under
*Keycloak*. This page covers the operator and end-user workflow once the
plugin is configured.

## How authentication works on the wire

Every Git-over-HTTP request to Gerrit carries an `Authorization` header:

```
Authorization: Basic <base64("<username>:<keycloak-access-token>")>
```

or

```
Authorization: Bearer <keycloak-access-token>
```

Gerrit's `ProjectOAuthFilter` extracts the token, hands it to this plugin's
`KeycloakOAuthLoginProvider`, which **validates the JWT locally** against
Keycloak's published JWKS:

```
GET <root-url>/realms/<realm>/protocol/openid-connect/certs   ← fetched on
                                                                 first
                                                                 validation,
                                                                 refetched on
                                                                 kid miss
                                                                 (60 s cooldown)
```

No per-push HTTP call to Keycloak. The plugin verifies, locally:

- **Signature** — against the public key whose `kid` matches the JWT header.
  Keys not in the cached JWKS trigger a single refetch attempt (rate-limited
  to one refetch per 60 s window) before rejecting.
- **Algorithm** — must be in the allowlist (`RS256`/`RS384`/`RS512`,
  `ES256`/`ES384`/`ES512`, `EdDSA`). Symmetric algorithms (`HS*`) and `none`
  are refused unconditionally — the former because consuming JWKS public
  keys with HMAC verification is the classic algorithm-confusion attack
  class (CVE-2015-9235 family).
- **`iss`** — must equal `<root-url>/realms/<realm>`.
- **`aud`** — must include this Gerrit's configured `client-id`. This closes
  the cross-client token-replay hole: tokens minted for a different client
  in the same realm are refused. **A default Keycloak realm does not put your
  client in the access token's `aud` — see *Audience mapper* below.**
- **`exp`** — must be in the future (with default 60 s clock-skew tolerance).
- **`nbf`** — when present, must not be in the future.

If all checks pass, the `preferred_username` / `email` / `name` claims from
the verified JWT payload become a Gerrit session. Any failure — malformed
JWT, unknown signing key, signature mismatch, expired, wrong audience,
wrong issuer, disallowed algorithm — surfaces to git as `401`.

There is **no token-result cache** on the Keycloak path. Local JWT
validation is sub-millisecond per push; caching the result would save
negligible work and add eviction surface. The browser flow's `id_token`
goes through the same validator for defence-in-depth.

Operators never need to do anything per-push. The work is in the *client-side*
acquisition of the access token, which is what the rest of this document
covers.

## Operator-visible behavior

A handful of properties of the JWKS-based design are worth knowing
before enabling Keycloak Git-over-HTTP:

1. **JWKS endpoint must be reachable from Gerrit.** Network ACLs that
   allow Gerrit → Keycloak `/token` need to also allow
   `<root-url>/realms/<realm>/protocol/openid-connect/certs`. Same
   host, usually no firewall change required.
2. **Audience is strictly checked.** Tokens minted for a different
   client in the same realm are rejected, even when otherwise valid.
   If automation in your environment shares tokens across multiple
   Keycloak clients, audit Keycloak's Events log for cross-client
   token use before enabling this feature.
3. **Revocation is bounded by `exp`.** A token revoked at Keycloak
   between issuance and `exp` remains valid to Gerrit until its
   `exp`. Operators relying on instant revocation should shorten
   Keycloak's access-token lifetime at the realm or client level —
   the default 5 min already satisfies most threat models. There is
   no server-side result cache on the Keycloak path: validation is
   local and stateless apart from the JWKS key cache.

## Prerequisites

- Gerrit configured with `auth.type = OAUTH`. Under it, Gerrit defaults
  `gitBasicAuthPolicy` to `OAUTH`, so a Basic-auth password is accepted as an
  OAuth token; a deployment that overrides that policy site-wide must set
  `auth.gitBasicAuthPolicy = OAUTH` explicitly.
- This plugin installed and configured for Keycloak (see `config.md`).
- **`enable-git-over-http = true`** on the Keycloak subsection of
  `gerrit.config`. Git-over-HTTP is opt-in per provider; without this
  flag the Keycloak login provider is *not* installed and only
  browser-based authentication works:

  ```
  [plugin "gerrit-oauth-provider-keycloak-oauth"]
    client-id = ...
    client-secret = ...
    enable-git-over-http = true
  ```

- The Keycloak client used by Gerrit (`client-id` in `gerrit.config`):
  - Is `confidential` (has a client secret).
  - Has *Valid Redirect URIs* set to `https://<your-gerrit>/oauth` — this is
    used for the browser SSO flow; it's a prerequisite for the same client to
    be used here even though Git-over-HTTP doesn't redirect.
  - **Does not** need `Direct Access Grants` enabled — that's the RoPC switch,
    which this guide deliberately avoids.
  - Has an **audience mapper** that adds this `client-id` to the access
    token's `aud` (see below).
- Exactly one OAuth provider may have Git-over-HTTP enabled. The plugin refuses
  to start if more than one is enabled; with only Keycloak enabled it is the
  single Git-over-HTTP provider.

## Keycloak-side configuration

The JWKS endpoint is part of Keycloak's standard OIDC surface and needs
no Keycloak-side switch to enable. **Access-token lifetime** is the one
detail worth tuning: Keycloak's default (5 min) is fine for most threat
models; for tighter revocation, shorten it at the realm or client level.
There is no plugin-side TTL to shorten — validation cost is local and
cached only on the IdP-key dimension.

### Audience: the access token must carry this Gerrit's client-id

The single most common cause of "the browser login works but `git` gets a
`401`" is the **audience claim**. The validator requires the access token's
`aud` to include the `client-id` configured in `gerrit.config`. But a
*default* Keycloak realm stamps `account` — not your client — into the
access token's `aud`, so a stock setup is rejected on the Git path even
though the browser `id_token` (whose `aud` *is* the client) works.

Add an *Audience* mapper so the access token carries your client:

1. Keycloak → *Clients → &lt;your Gerrit client&gt; → Client scopes →
   &lt;client&gt;-dedicated → Add mapper → By configuration → Audience*.
2. Set *Included Client Audience* to your Gerrit `client-id`.
3. Ensure *Add to access token* is On. Save.

Verify with *Verifying the token works without git* below: decode the token
and confirm `aud` contains your `client-id`. This is mandatory on the Git
path; do **not** work around it by relaxing the audience check — the check is
what stops a token minted for another client from being replayed against
Gerrit.

### Required claims: preferred_username, email, name

The plugin maps the **access token's** `preferred_username`, `email`, and
`name` claims onto the Gerrit account and rejects a token missing any of them
(even one that passes signature / issuer / audience). Because Git presents the
*access* token (not the `id_token`), those claims must be present in the
**access token**, which means:

- Request the token with **`scope=openid profile email`** (the `profile` scope
  supplies `preferred_username` and `name`; `email` supplies `email`). These
  are default client scopes in a stock Keycloak, but they are only emitted when
  requested.
- Ensure the underlying user actually has an email and a name. For a **service
  account** (Approach 1), the `service-account-<client-id>` user has neither by
  default — set its *Email*, *First name*, and *Last name* under *Users*, and
  make sure the client's *profile* / *email* scopes are on its access token (or
  add explicit protocol mappers).

A token that validates but lacks these claims produces a `401` with, e.g.,
"Response doesn't contain preferred_username field" in the Gerrit log.

### Optional: PKCE for the browser flow

Set `enable-pkce = true` on the Keycloak subsection to use PKCE
(Proof Key for Code Exchange, RFC 7636) on the browser authorization-code
flow: the plugin generates a `code_verifier` per request, sends the SHA-256
challenge to Keycloak's `/auth` endpoint, and posts the verifier back on the
`/token` exchange. *Public* Keycloak clients require PKCE; *confidential*
clients can opt in for defence-in-depth with no Keycloak-side change. Default
is `false`. This is independent of Git-over-HTTP — it only affects the
browser flow.

### Optional: token refresh

Set `enable-token-refresh = true` (default `false`) to make Keycloak
refresh-capable. It is consumed by the core `gerrit oauth-token get` command
(and by `GetOAuthToken` on read), which renews an expired token from its refresh
token when the issuing provider has `enable-token-refresh` — it does **not** need
`enable-git-over-http`.

The plugin does **not** request `offline_access`: it relies on the session-bound
refresh token Keycloak returns for the authorization-code flow, so refresh works
for the SSO session lifetime. To let it survive long idle periods or logout,
grant `offline_access` in Keycloak; Gerrit does not force it.

As with any provider, the refreshed token is renewed at retrieval time (the
`oauth-token` SSH command, or on browser/REST read via `OAuthTokenRefresher`),
not during a Git-over-HTTP request. Refresh tokens are persisted in core's
`oauth_tokens` cache; see the core `auth.tokenEncryptionKey` documentation
for encrypting the stored tokens.

## Approach 1 — Keycloak service account (for CI / automation)

This is the right approach for CI pipelines, release automation, mirroring
jobs, anything headless. No human is in the loop, no password is ever typed,
and Keycloak's audit log shows a dedicated "robot user" rather than a real
employee's account.

### One-time Keycloak setup

You need a Keycloak client whose `client_credentials` grant produces a token
Gerrit will accept. The simplest setup is one client per automation use case:

1. In Keycloak, *Clients → Create client*. Give it a sensible ID (e.g.
   `gerrit-ci-build-bot`). Set:
   - *Client authentication*: On (confidential).
   - *Service accounts roles*: On (this enables `client_credentials`).
2. Save. Go to the *Credentials* tab, copy the client secret.
3. *Service Account Roles* tab → assign whatever realm/client roles your
   Gerrit ACLs key off.
4. Add an *Audience* mapper (as above) so the service account's access token
   carries the Gerrit `client-id` in `aud`.
5. The service account user appears under *Users* as `service-account-<client-id>`.
   This is what Gerrit will see as the authenticated user.

### Per-job token acquisition

```bash
TOKEN=$(curl -sS -X POST \
  -d grant_type=client_credentials \
  -d client_id=gerrit-ci-build-bot \
  -d client_secret="$KC_CLIENT_SECRET" \
  -d scope="openid profile email" \
  https://keycloak.example.com/realms/<realm>/protocol/openid-connect/token \
  | jq -r .access_token)
```

### Using the token with git

Two options.

**Bearer header (cleaner — no fake username):**

```bash
git -c http.extraHeader="Authorization: Bearer $TOKEN" \
    push https://gerrit.example.com/a/myproject HEAD:refs/for/master
```

The `/a/` path prefix tells Gerrit to require authentication.

**Basic auth (for git versions that ignore `http.extraHeader`):** feed the
token to git's credential helper rather than putting it in the URL — a token
in the URL leaks into shell history, `ps`/process listings, and git's trace
logs, and can be mangled by URL escaping:

```bash
printf 'protocol=https\nhost=gerrit.example.com\nusername=service-account-gerrit-ci-build-bot\npassword=%s\n' "$TOKEN" \
  | git -c credential.helper='cache --timeout=300' credential approve
git -c credential.helper='cache --timeout=300' \
    push https://gerrit.example.com/a/myproject HEAD:refs/for/master
```

The username must be the Keycloak `preferred_username` of the service account
(`service-account-<client-id>` by default).

### Token lifetime

Keycloak client tokens default to a short lifetime (commonly 5 minutes). For
short jobs (push, fetch, query), acquire one fresh per job and don't worry
about it. For long-running jobs (e.g. multi-hour replication), re-request a
fresh `client_credentials` token before each git invocation, or before the
current one's `expires_in` runs low. The `client_credentials` grant does
**not** issue a `refresh_token`, so obtain a new access token from `/token`
rather than trying to refresh one.

## Approach 2 — Git credential helper (for developers)

Developers don't want to think about tokens. A credential helper makes git
acquire one transparently the first time you push, then cache it.

### Option A: `git-credential-oauth`

The [git-credential-oauth](https://github.com/hickford/git-credential-oauth)
helper supports arbitrary OIDC issuers via PKCE-on-loopback. It opens a
browser the first time, the user logs into Keycloak normally (MFA, SSO,
whatever Keycloak is configured for), and the resulting token is cached in
the OS keychain.

Install (per the helper's README), then configure git for your Gerrit host:

```bash
git config --global credential.https://gerrit.example.com.helper "oauth \
  --client-id=gerrit-cli \
  --authorize-url=https://keycloak.example.com/realms/<realm>/protocol/openid-connect/auth \
  --token-url=https://keycloak.example.com/realms/<realm>/protocol/openid-connect/token \
  --scopes=\"openid profile email\""
```

`gerrit-cli` here is a *separate* public Keycloak client (not the Gerrit
backend's confidential client). Create it in Keycloak with:

- *Client authentication*: Off (public client — no secret).
- *Standard flow*: On.
- *Valid Redirect URIs*: `http://127.0.0.1/*` (loopback for PKCE).
- *Web Origins*: `+`.
- An *Audience* mapper adding the Gerrit backend's `client-id`, so the token
  this client mints is accepted by Gerrit.

After the first push, the user is prompted in their browser. Subsequent
pushes use the cached token until it expires.

### Option B: Personal Access Token (no helper)

Some teams prefer to issue a long-lived token out-of-band — typically with the
`offline_access` scope which gives Keycloak refresh tokens that don't expire.
Trade-off: longer-lived credential on disk, but no per-push browser round
trip. Acquire once via Approach 3, drop into `~/.netrc` or the OS keychain.

## Approach 3 — One-shot manual (debugging / first-time setup)

When you just want to confirm the plumbing works, before bothering with
helpers:

```bash
# Use the Direct Access Grants flow on a throwaway interactive client,
# or use a service account as in Approach 1.
TOKEN=$(curl -sS -X POST \
  -d grant_type=password \
  -d client_id=<your-confidential-client> \
  -d client_secret=<secret> \
  -d username=<your-keycloak-user> \
  -d password=<your-keycloak-password> \
  -d scope="openid profile email" \
  https://keycloak.example.com/realms/<realm>/protocol/openid-connect/token \
  | jq -r .access_token)

git -c http.extraHeader="Authorization: Bearer $TOKEN" \
  ls-remote -h https://gerrit.example.com/a/myproject
```

Yes, this *is* RoPC under the hood — but you're only using it for a one-shot
manual test against your own account, not as the production code path. The
moment you wire it into anything that runs unattended, switch to Approach 1.

## Verifying the token works without git

If git pushes fail with `401`, isolate whether the problem is your token or
git's plumbing:

```bash
# Decode the JWT payload to inspect its claims (no signature verification —
# just look at iss, aud, exp, preferred_username, email, name). JWT payloads
# are base64url and often unpadded, so decode them base64url-safely rather than
# with `base64 -d` (which differs across macOS/Linux and rejects unpadded url
# encoding):
python3 -c 'import sys,base64,json; p=sys.argv[1].split(".")[1]; p+="="*(-len(p)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(p)),indent=2))' "$TOKEN"

# Should hit Gerrit's authenticated REST and return JSON about your account.
curl -sS \
  -H "Authorization: Bearer $TOKEN" \
  https://gerrit.example.com/a/accounts/self
```

If the decoded payload shows the right `iss`, `aud == <client-id>`, and a
future `exp`, but Gerrit still returns 401, the problem is on the Gerrit
side (account lookup failing, plugin not picked as the Git-over-HTTP
provider, or the realm's JWKS isn't reachable from Gerrit). If `aud` shows
`account` instead of your `client-id`, add the *Audience* mapper above.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `401` on every push, even with a brand-new token whose `aud` is `account` | No *Audience* mapper on the Keycloak client, so the access token's `aud` is `account` rather than your `client-id`. Add the mapper (see *Audience* above). |
| `401` on the second push of the day | Token expired. Default Keycloak access-token lifetime is 5 min. For user tokens use a credential helper or refresh-token loop; for `client_credentials` re-request a fresh access token (that grant issues no refresh token). |
| `401` after rotating the Keycloak client secret | Service accounts only — the cached `$TOKEN` was minted with the old secret; obtain a new one. |
| `401` with "JWT validation failed" and audience in the Gerrit log | Token was minted for a different Keycloak client than the one in `gerrit.config`, or the audience mapper is missing. Confirm `aud` contains the same `client-id`. |
| `401` with "JWT issuer not accepted" | The token's `iss` doesn't match `<root-url>/realms/<realm>`. Most often: someone hit the wrong realm during token acquisition. |
| `401` with "unknown signing key" or "no JWK matches keyID" | Keycloak rotated its signing keys, and Gerrit's JWKS cache hasn't refetched. Wait 60 s for the cooldown to lapse and retry — the next push triggers a refresh. If it persists, check Gerrit ↔ `/protocol/openid-connect/certs` connectivity. |
| `401` with "Authentication error: username does not match" | The `preferred_username` Keycloak returns doesn't match the username supplied in the Basic-auth header. With `use-preferred-username = false`, no username is returned and this check is skipped. |
| `403 forbidden` after authenticating | Authentication succeeded but the user lacks ACL permission. Distinguish from `401` by the Gerrit log line. |
| Revoked token still works | JWKS validation can't detect revocation in real time — a revoked token stays valid until its `exp`. Shorten the access-token lifetime in Keycloak (realm or client level) to bound the window. |

## Why no RoPC support in the plugin?

The plugin deliberately doesn't expose an `enable-resource-owner-password-flow`
flag for Keycloak (which the SAP IAS provider does have, for historical
reasons). Three reasons:

1. **The OAuth working group has retired it.** RFC 9700 (OAuth 2.0 Security
   Best Current Practice, 2025) states: *"the resource owner password
   credentials grant MUST NOT be used."*
2. **It bypasses MFA.** Any browser-flow protection your Keycloak realm
   enforces (TOTP, WebAuthn, conditional access) is silently skipped when a
   client posts a username and password to `/token`.
3. **Gerrit would see the user's raw Keycloak password.** Every git push would
   put a plaintext password in JVM memory for the duration of the request.
   Heap dumps, log misconfiguration, or a JVM agent could exfiltrate it.

Every legitimate use case for RoPC has a modern alternative covered above:
service accounts for automation, credential helpers for developers, manual
one-shot for debugging. If you find a scenario this guide doesn't cover,
please open an issue describing it before reaching for RoPC.
