Git-over-HTTP with GitHub (experimental)
========================================

> **Experimental.** Git-over-HTTP support for the GitHub provider is opt-in.
> GitHub access tokens are opaque, so they are validated remotely at GitHub's
> "check a token" endpoint and successful, expiring results are cached.
> Operational details (log lines, cache behaviour, claim handling) may change
> between minor versions.

How to push and fetch over HTTPS against a Gerrit instance whose OAuth provider
is GitHub (github.com or GitHub Enterprise Server). The plugin-side configuration
is in [config.md](config.md); this page covers the operator and end-user workflow
once the plugin is configured.

## What the client presents: an access token issued for Gerrit's OAuth app

GitHub is not OIDC and issues opaque tokens, so the Git credential cannot be
validated locally. The plugin introspects it at GitHub's "check a token" endpoint
(`POST /applications/{client_id}/token`), authenticating **as the OAuth app**
with `client-id:client-secret`. The token must be one **issued for this app**:

- a classic OAuth-App user token (`gho_*`), or
- a GitHub-App OAuth user token (`ghu_*`).

An HTTP `200` means the token exists and was issued for this app — GitHub's
audience check. A token issued for a *different* app returns `404` and is
**rejected**; a classic personal access token (`ghp_*`) is not tied to an app and
is likewise rejected. The response's `app.client_id` is re-checked against the
configured `client-id`.

The browser login flow still fetches the profile (`id`, `login`, `email`, `name`)
from `GET /user`, but now also validates the token at check-token and binds
check-token's user `id` to the `/user` `id`, so the browser token gets the same
per-app audience check.

## Prerequisite — both client-id and client-secret are required

The check-token endpoint is authenticated as the OAuth app, so **both**
`client-id` and `client-secret` must be configured. If either is missing the
plugin refuses to install the GitHub Git path and Gerrit fails to start with:

```
GitHub token validation requires both client-id and client-secret to introspect
tokens at the check-token endpoint.
```

## Configuration

```
[auth]
  type = OAUTH
  gitBasicAuthPolicy = OAUTH

[plugin "gerrit-oauth-provider-github-oauth"]
  root-url = https://github.com/     # or https://github.example.com/ for GHES
  client-id = ...
  client-secret = ...
  enable-git-over-http = true
  # enable-pkce = true      # optional; when true the browser authorization-code flow uses PKCE
```

- **`auth.type = OAUTH`** is required. With it, Gerrit already defaults
  `gitBasicAuthPolicy` to `OAUTH`, so a Basic-auth password is accepted as an
  OAuth token. The `gitBasicAuthPolicy = OAUTH` line is shown explicitly so a
  deployment that overrides that policy site-wide (e.g. to `HTTP` or `LDAP`) does
  not accidentally route Git/HTTP through password auth instead.
- **`root-url`** selects the API base: `https://github.com/` uses
  `https://api.github.com/`; a GitHub Enterprise Server URL uses that host's
  `.../api/v3/`.
- The check-token call is made only over `https` or to a loopback host, so a
  mistyped GHES `root-url` cannot leak the token in cleartext. Point `root-url` at
  an `https` GHES (or a loopback host for local testing).
- Git-over-HTTP is **opt-in per provider**; without `enable-git-over-http = true`
  the GitHub Git path is not installed and only browser login works.
- Exactly one OAuth provider may have `enable-git-over-http = true`. If more than
  one is enabled the plugin refuses to start.

## How validation works on the wire

Every Git-over-HTTP request carries an `Authorization` header:

```
Authorization: Basic <base64("<username>:<access-token>")>
```

or

```
Authorization: Bearer <access-token>
```

The plugin's `GitHubOAuthLoginProvider` POSTs the token to
`.../applications/{client_id}/token`, authenticated as the app, and:

- treats `200` as a token issued for this app (`404`/other statuses are rejected);
- re-checks the response's `app.client_id` equals the configured `client-id`;
- maps the response `user.id` to the external id and `user.login` to the username.

Any failure surfaces to git as `401`.

## Validation-result cache

Each opaque-token validation is one HTTPS call to check-token. To avoid
re-validating the same token for every object during a push or fetch, successful
results are cached in the `oauth_token_validation` cache, keyed by a SHA-256 of
the token (never the raw token). An entry lives for the **lesser of** the token's
own expiry and a hard TTL (60 s by default). Only successful validations are
cached; failures never are.

Note the token type matters here:

- **GitHub-App user tokens (`ghu_*`)** expire, so check-token returns
  `expires_at` and the result is cached until that instant (bounded by the hard
  TTL).
- **Classic OAuth-App tokens (`gho_*`)** do not expire and carry no `expires_at`;
  with no upper bound they are **not cached**, so each request re-checks at
  GitHub. Lower `maxAge` does not change this — it only bounds the expiring case.

Tune it in `gerrit.config`. The cache is plugin-qualified, so the section name is
`<plugin-name>.oauth_token_validation` (plugin name, a dot, then the cache name):

```
[cache "gerrit-oauth-provider.oauth_token_validation"]
  maxAge = 60s        # hard upper bound on how long a validation result is reused
  memoryLimit = 10000 # maximum number of cached tokens
```

Hit-rate and eviction statistics (the REST/registry name uses hyphens):
`/a/config/server/caches/gerrit-oauth-provider-oauth_token_validation`.

Under high-availability or multi-site this cache is **not** eviction-synced by
default (their default patterns cover only core caches). It is a short-lived
(default 60 s `maxAge`) in-memory cache keyed by a token hash, so independent
per-node validation is usually fine. To sync evictions anyway, add a repeatable
`cache.pattern` (singular) entry in the plugin's config
(`high-availability.config` or `multi-site.config`) matching the dot-qualified
name:

    [cache]
      pattern = gerrit-oauth-provider.oauth_token_validation

## Token acquisition

The credential is a GitHub access token issued for **Gerrit's** OAuth app.

Developer machines must **not** be given Gerrit's `client-secret`. Acquire the
token with a flow that does not need it — for example the GitHub **OAuth device
flow** on Gerrit's OAuth app (client-id only), via a credential helper such as
`git-credential-oauth`. The device flow works only if the OAuth app has *Enable
Device Flow* turned on in its GitHub settings. Reserve secret-bearing acquisition
(the authorization-code grant that uses `client-secret`) for trusted,
operator-controlled automation.

Present the token without putting it in the URL — a token in the URL leaks into
shell history, `ps`, and git trace logs:

```bash
git -c http.extraHeader="Authorization: Bearer $TOKEN" \
  ls-remote -h https://gerrit.example.com/a/myproject
```

A classic personal access token (`ghp_*`) will not work — it is not issued for
the OAuth app and check-token rejects it.

## Verifying the token without git

Introspect the token the same way the plugin does (as the app):

```bash
curl -sS -u "<client-id>:<client-secret>" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/applications/<client-id>/token \
  -d '{"access_token":"'"$TOKEN"'"}'
```

On GitHub Enterprise Server use the host's `/api/v3/` base instead:
`https://github.example.com/api/v3/applications/<client-id>/token`.

A `200` with an `app.client_id` equal to your `client-id` means the token is
valid for this app; `404` means it was issued for a different app (or revoked).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Startup error "requires both client-id and client-secret" | Only one of `client-id`/`client-secret` is set. Configure both. |
| `401` with "does not belong to this Gerrit's GitHub OAuth app" | The token was issued for a different app, or is a `ghp_*` PAT. Use a token issued for Gerrit's OAuth app. |
| `401` with "rejected client credentials (HTTP 401)" | Gerrit's `client-id`/`client-secret` are wrong, not the user's token. |
| `401` with "belongs to a different GitHub OAuth app" | The check-token response's `app.client_id` did not match the configured `client-id`. |
| `401` with "Authentication error: username does not match" | The client-supplied username differs from the token owner's GitHub login. |
| `403 forbidden` after authenticating | Authentication succeeded but the user lacks ACL permission. |
