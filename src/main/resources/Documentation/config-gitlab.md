Git-over-HTTP with GitLab (experimental)
========================================

> **Experimental.** Git-over-HTTP support for the GitLab provider is opt-in.

## What the client presents: an opaque GitLab access token

GitLab OAuth access tokens are **opaque** (not JWTs), so they cannot be validated
locally. The plugin introspects the token at GitLab's `/oauth/token/info`
endpoint and then reads the profile from `/api/v4/user`. The token must:

- be issued for a **trusted OAuth application** — `token/info`'s
  `application.uid` must be the configured `client-id` or one of the
  `trusted-audience` entries. A token minted for any other GitLab application is
  **rejected**;
- carry the **required scope(s)** — `token/info`'s `scope` must include every
  `required-scope` (default `read_user`);
- resolve to a user whose `token/info` `resource_owner_id` matches the
  `/api/v4/user` `id` (subject binding).

`Personal`, `Project`, and `Group` access tokens are **not** OAuth-application
tokens; they carry no matching `application.uid` and are therefore rejected.

## Configuration

```
[auth]
  type = OAUTH
  gitBasicAuthPolicy = OAUTH

[plugin "gerrit-oauth-provider-gitlab-oauth"]
  root-url = https://gitlab.com/     # or https://gitlab.example.com/ for self-managed
  client-id = ...
  client-secret = ...
  enable-git-over-http = true
  # required-scope = read_user       # optional, repeatable; default read_user
  # trusted-audience = <helper-client-id>  # optional, repeatable; see "Token acquisition"
```

- **`auth.type = OAUTH`** is required; `gitBasicAuthPolicy = OAUTH` is shown
  explicitly so a site-wide override does not route Git/HTTP through password auth.
- Git-over-HTTP is **opt-in per provider**; without `enable-git-over-http = true`
  the GitLab Git path is not installed and only browser login works.
- Exactly one OAuth provider may have `enable-git-over-http = true`.
- **`required-scope`** (repeatable) is the defense-in-depth scope gate. It
  defaults to `read_user` (the minimum for `/api/v4/user`); set it to `api` or a
  narrower custom scope to require more.
- **`trusted-audience`** (repeatable, optional) lists additional GitLab OAuth
  application ids (`application.uid`s) whose tokens the Git path accepts, beyond
  `client-id`. Leave it unset and only browser (`client-id`) tokens pass —
  existing deployments are unchanged. Set it to register a separate client (a git
  credential helper's own client, or a CI client); see "Token acquisition".
- `token/info` and `/api/v4/user` are called only over `https` or to a loopback
  host, so a mistyped `root-url` cannot leak the token in cleartext. Point
  `root-url` at an `https` GitLab (or a loopback host for local testing).

### Token refresh

Set `enable-token-refresh = true` (default `false`) to make GitLab
refresh-capable; GitLab issues a refresh_token for the authorization-code flow
by default, so no extra scope is requested. An expired token is renewed on
read:

- browser/REST reads (`GET /accounts/{id}/oauthtoken`) refresh it via
  `OAuthTokenRefresher` before returning it; and
- the core `oauth-token` SSH command refreshes on demand and needs only
  `enable-token-refresh` on the issuing provider — not `enable-git-over-http`.

Neither refreshes during a Git-over-HTTP request, which validates the presented
opaque token via `/oauth/token/info` as before.

## How validation works on the wire

Every Git-over-HTTP request carries an `Authorization` header:

```
Authorization: Basic <base64("<username>:<access-token>")>
```

or

```
Authorization: Bearer <access-token>
```

The plugin's `GitLabTokenInfoValidator`:

- `GET <root>/oauth/token/info` (Bearer) — checks `application.uid` is a trusted
  audience (`client-id` or a `trusted-audience` entry), `scope` ⊇
  `required-scope`, and reads `resource_owner_id`;
- `GET <root>/api/v4/user` (Bearer) — reads `id`/`username`/`email`/`name`/`state`;
- binds `resource_owner_id` == `/api/v4/user` `id`, mapping the user the same way
  the browser flow does (shared `GitLabUserInfoMapper`);
- rejects the token unless `state` is `active`, so a valid token for a `blocked`,
  `deactivated`, `banned`, or `ldap_blocked` account cannot push;
- caches successes (see below). Any failure surfaces to git as `401`.

## Validation-result cache

Successful validations are cached in the `oauth_token_validation` cache (keyed by
a SHA-256 of the token, never the raw token) for the lesser of the token's own
expiry (`expires_in` from `token/info`) and a hard TTL (60 s by default). Tokens
without an `expires_in` are not cached. Tune it under the plugin-qualified name:

```
[cache "gerrit-oauth-provider.oauth_token_validation"]
  maxAge = 60s
  memoryLimit = 10000
```

Statistics (the REST/registry name uses hyphens):
`/a/config/server/caches/gerrit-oauth-provider-oauth_token_validation`.

## Token acquisition (git credential helpers)

Unlike Google, GitLab has **true public OAuth clients** (PKCE, loopback, device
flow), so off-the-shelf credential helpers such as `git-credential-oauth` can
obtain and refresh tokens for `git clone`/`fetch`/`push`. The Git path accepts a
token only if its `token/info` `application.uid` is a trusted audience, so the
helper's client must be one Gerrit trusts.

**Recommended: a separate public helper app + `trusted-audience`.** Register a
dedicated **non-confidential** GitLab OAuth application for the helper (public /
PKCE, no client secret), keep Gerrit's browser `client-id` for SSO, and list the
helper's client id in `trusted-audience`:

```
[plugin "gerrit-oauth-provider-gitlab-oauth"]
  client-id        = <browser-client-id>   # Gerrit browser SSO
  trusted-audience = <helper-client-id>    # git credential helper / CI client
  enable-git-over-http = true
```

A public helper app needs no secret to distribute, which is exactly why a normal
helper can use it. Gerrit's browser SSO app is typically **confidential** (has a
secret); reusing it for a helper would mean handing that secret to every client,
defeating the point of a separate audience.

**Alternative: point the helper at Gerrit's `client-id`.** Only appropriate if
Gerrit's app is itself usable as a public/PKCE client, or for trusted,
secret-capable automation where distributing the client secret is acceptable.
For `git-credential-oauth`, set `oauthClientId` to that application.

**git-credential-oauth endpoint gotcha.** Configure the helper under the **Gerrit
remote** host (that is the URL git authenticates against), but point its OAuth
endpoints at **GitLab** — the helper cannot infer a GitLab host from a
`https://gerrit.example.com/...` remote, and a relative `/oauth/authorize` would
resolve against Gerrit, not GitLab:

```
git config --global credential.https://gerrit.example.com.helper oauth
git config --global credential.https://gerrit.example.com.oauthClientId <helper-client-id>
git config --global credential.https://gerrit.example.com.oauthScopes read_user
git config --global credential.https://gerrit.example.com.oauthAuthURL https://gitlab.example.com/oauth/authorize
git config --global credential.https://gerrit.example.com.oauthTokenURL https://gitlab.example.com/oauth/token
```

The `read_user` scope above is sized **for Gerrit validation only** — it is all
the validator needs (`application.uid` + `/api/v4/user` + `required-scope`). Add
any other scopes your helper or workflow expects (upstream GitLab examples often
add `read_repository`/`write_repository`); a too-narrow scope surfaces as a
*helper-side* failure, not a Gerrit auth failure.

Either way, request at least `read_user` (or whatever `required-scope` demands).
Personal/Project/Group access tokens carry no `application.uid` and are rejected.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `401`, "Token belongs to a different GitLab application" | The token's `application.uid` is neither `client-id` nor a `trusted-audience` entry. Use a token issued for a trusted app, or add its client id to `trusted-audience`. |
| `401`, "Token is missing the required scope" | The token lacks a `required-scope` value (default `read_user`). |
| `401`, subject mismatch | `token/info` `resource_owner_id` did not match the `/api/v4/user` id. |
