Git-over-HTTP with Google (experimental)
========================================

> **Experimental.** Git-over-HTTP support for the Google provider is opt-in.
> Google access tokens are opaque, so they are validated remotely at Google's
> `tokeninfo` endpoint and successful results are cached. Operational details
> (log lines, cache behaviour, claim handling) may change between minor versions.

How to push and fetch over HTTPS against a Gerrit instance whose OAuth provider
is Google. The plugin-side configuration is in [config.md](config.md); this page
covers the operator and end-user workflow once the plugin is configured.

## What the client presents: an opaque Google access token

Google does not issue JWT access tokens usable for Git; the Git credential is an
**opaque** access token (`ya29.*`). It cannot be validated locally, so the
plugin introspects it at Google's `tokeninfo` endpoint. The token must:

- be issued for a **trusted client** — its `aud`/`audience` (and `azp`/`issued_to`,
  when present) must equal Gerrit's `client-id`, or one of the optional
  `trusted-audience` entries, normalised to end with
  `.apps.googleusercontent.com`. A token minted for any other Google client is
  **rejected**;
- carry a **verified email** (`email_verified`/`verified_email` is `true`);
- carry `sub`/`user_id` (used as the Gerrit external id) and `email` (the
  `email` scope). `tokeninfo` returns no display name.

The browser login flow is unchanged: it still reads the `id_token` and userinfo,
now with an `id_token` JWKS check bound to the userinfo subject.

## Prerequisite — hosted-domain restriction is not supported on the Git path

The browser flow enforces a configured `domain` (hosted-domain) restriction from
the `id_token`'s verified `hd` claim. Google's `tokeninfo` response for an access
token does **not** carry `hd`, so the Git path cannot enforce it. Rather than
silently admit users outside the allowed domain over Git, the plugin **refuses to
install** the Google Git-over-HTTP login provider when `domain` is set, and
Gerrit fails to start with:

```
Google Git-over-HTTP cannot enforce the configured hosted-domain (domain)
restriction: Google's tokeninfo response for an access token does not include the
hd claim. Remove the domain restriction, or set enable-git-over-http = false for
Google.
```

If you rely on `domain`, keep `enable-git-over-http = false` for Google and use a
different provider (or none) for Git-over-HTTP.

## Configuration

```
[auth]
  type = OAUTH
  gitBasicAuthPolicy = OAUTH

[plugin "gerrit-oauth-provider-google-oauth"]
  client-id = ...
  client-secret = ...
  enable-git-over-http = true
  # enable-pkce = true      # optional; when true the browser authorization-code flow uses PKCE
  # trusted-audience = <cli-client-id>   # optional; see "Standard git credential helpers"
  # trusted-audience = <ci-client-id>    # repeatable
  # domain = ...            # NOT compatible with enable-git-over-http (see above)
  # use-email-as-username = true   # optional; derives the username from the email local part
```

- **`auth.type = OAUTH`** is required. With it, Gerrit already defaults
  `gitBasicAuthPolicy` to `OAUTH`, so a Basic-auth password is accepted as an
  OAuth token. The `gitBasicAuthPolicy = OAUTH` line is shown explicitly so a
  deployment that overrides that policy site-wide (e.g. to `HTTP` or `LDAP`) does
  not accidentally route Git/HTTP through password auth instead.
- Git-over-HTTP is **opt-in per provider**; without `enable-git-over-http = true`
  the Google Git path is not installed and only browser login works.
- Exactly one OAuth provider may have `enable-git-over-http = true`. If more than
  one is enabled the plugin refuses to start.
- **`trusted-audience`** (repeatable, optional) lists additional Google
  `client-id`s whose tokens the Git path accepts, beyond `client-id`. Leave it
  unset and only browser (`client-id`) tokens are accepted — existing
  deployments are unchanged. Set it to register a separate **Desktop** client for
  standard git credential helpers or a CI client; see
  [Git credential helpers](#git-credential-helpers-auto-refresh).

## How validation works on the wire

Every Git-over-HTTP request carries an `Authorization` header:

```
Authorization: Basic <base64("<username>:<access-token>")>
```

or

```
Authorization: Bearer <access-token>
```

The plugin's `GoogleOAuthLoginProvider` validates the token by calling Google's
`tokeninfo` endpoint (`https://oauth2.googleapis.com/tokeninfo`) with the token as
a query parameter, and:

- checks `aud`/`audience` is one of the trusted audiences (Gerrit's `client-id`
  plus any `trusted-audience` entries);
- checks `azp`/`issued_to` (when present) is also a trusted audience — a token
  whose audience is us but that was authorized to a different client is rejected
  (confused-deputy defense);
- requires `email_verified`/`verified_email` to be `true`;
- requires `sub`/`user_id`, mapping it to the external id.

On the Git path the external id comes from `sub`/`user_id` and the email from
`email`; `tokeninfo` returns no display name, so none is set. With
`use-email-as-username = true` the username is the email local part. Any failure
surfaces to git as `401`.

## Validation-result cache

Each opaque-token validation is one HTTPS call to `tokeninfo`. To avoid
re-validating the same token for every object during a push or fetch, successful
results are cached in the `oauth_token_validation` cache, keyed by a SHA-256 of
the token (never the raw token). An entry lives for the **lesser of** the token's
own expiry (`expires_in` from `tokeninfo`) and a hard TTL (60 s by default). Only
successful validations are cached; failures never are.

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

## Git credential helpers (auto-refresh)

The nicest client experience is a git credential helper: authenticate in the
browser once, then `git clone` / `fetch` / `push` "just work" while the helper
mints and refreshes short-lived access tokens silently. `trusted-audience` is
what makes this possible — it lets a dedicated **git CLI** client be used
alongside the browser SSO client.

### The Google constraint (read first)

For the Google **Desktop-client** flow used here, the token exchange (and
refresh) requires sending the `client_secret`. A helper's **default, secretless
public-client mode** (what native helpers use for GitHub/GitLab, which *do* have
public clients) therefore fails against Google; the helper must be configured to
**send the secret**. The Desktop secret is not confidential (see below), so this
is safe.

Options:

- The reference helper `contrib/git-credential-google-gerrit` (prompts for the
  secret; see the repo-root `contrib/README.md`), or an operator-run token broker.
- A secret-capable maintained helper — **Git Credential Manager** (generic OAuth
  with `oauthClientSecret`), or **`git-credential-oauth`** (via
  `credential.oauthClientSecret`) — configured with the **Desktop** client id
  *and* secret, Google's endpoints, and `trusted-audience`. Their out-of-the-box
  secretless config will not work with Google.

Because a Desktop client's secret is **not confidential** (see below), putting it
in a helper's config is acceptable; never use Gerrit's confidential **Web SSO**
secret this way.

### Two-client model

- **Web application** client (`<Org> Gerrit Web SSO`) — browser login; secret in
  `secure.config` and genuinely confidential.
- **Desktop app** client (`<Org> Gerrit Git CLI`) — git credential helpers. It
  also has a secret, but for a Desktop client that secret is **not confidential**
  (it is distributed to users); Google merely requires it in the exchange.
  Desktop clients accept any `http://localhost:<port>` loopback, so there is no
  redirect URI to register.

### Operator setup

1. In Google Cloud Console → Credentials, create the **Desktop app** client. Keep
   the Web client for browser login.
2. List the Desktop client-id in `trusted-audience`:

   ```
   [plugin "gerrit-oauth-provider-google-oauth"]
     client-id        = <web-client-id>      # Gerrit Web SSO (browser)
     client-secret    = <web-client-secret>  # in secure.config
     trusted-audience = <cli-client-id>      # Gerrit Git CLI (helpers)
     enable-git-over-http = true
   ```

### User setup (reference helper, macOS/Linux)

Prerequisite: set a Gerrit **username** (Settings → Username). The credential
helper uses Basic auth, and the Basic path resolves your account by username, so
one must exist.

1. Authenticate once (opens a browser; prompts for the Desktop client secret,
   hidden — never argv/history/env):

   ```bash
   git-credential-google-gerrit login \
     --host https://gerrit.example.com \
     --client-id <cli-client-id> \
     --gerrit-username <username>
   ```

2. Enable the helper for your Gerrit host. The empty first value resets the
   helper chain so a platform keychain (e.g. `osxkeychain`) does not cache the
   short-lived access token ahead of this helper:

   ```bash
   git config --global credential.https://gerrit.example.com.helper ""
   git config --global --add credential.https://gerrit.example.com.helper google-gerrit
   git config --global credential.https://gerrit.example.com.username <username>
   ```

3. Use git normally:

   ```bash
   git clone https://gerrit.example.com/a/myproject
   ```

No token is pasted. The helper mints a Desktop-client token; Gerrit accepts it
because its `aud` is in `trusted-audience`, and refreshes happen silently on each
git operation. The confused-deputy defense is preserved: only client-ids you list
are trusted.

### Browserless / CI (device flow)

The loopback flow above needs a local browser. A headless host (CI, a remote
shell) needs OAuth **device flow**, which Google serves only for a **"TVs and
Limited Input devices"** client — a *third* client type (also secret-required).
Register one, list its client-id in `trusted-audience` too, and drive it from a
device-flow-capable acquisition step or broker.

## Token acquisition

Without a helper, the credential must be a Google access token whose `aud` (and
`azp`/`issued_to`, when present) is one of the trusted audiences — Gerrit's
`client-id` or a `trusted-audience` entry. Request the `email` scope so
`tokeninfo` reports a verified email.

Developer machines must **not** be given Gerrit's Web `client-secret`. Use a
Desktop client via `trusted-audience` (above), acquire tokens through trusted,
operator-controlled automation (an acquisition broker), or keep Google
Git-over-HTTP disabled.

Present the token without putting it in the URL — a token in the URL leaks into
shell history, `ps`, and git trace logs:

```bash
git -c http.extraHeader="Authorization: Bearer $TOKEN" \
  ls-remote -h https://gerrit.example.com/a/myproject
```

Google access tokens are short-lived (about an hour), so acquire a fresh one per
job or refresh before expiry.

## Verifying the token without git

Introspect the token the same way the plugin does:

```bash
curl -sS "https://oauth2.googleapis.com/tokeninfo?access_token=$TOKEN"
```

Confirm `aud` (or `audience`) is one of the trusted audiences (Gerrit's
`client-id` or a `trusted-audience` entry), `email_verified` (or
`verified_email`) is `true`, and `sub` (or `user_id`) is present.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Startup error "cannot enforce the configured hosted-domain (domain) restriction" | `domain` is set together with `enable-git-over-http = true`. Remove `domain`, or disable Git-over-HTTP for Google. |
| `401` with wrong-audience error | The token was issued for an untrusted Google client. Use a token issued for Gerrit's `client-id`, or add the client to `trusted-audience`. |
| `401` with "email is not verified" | The Google account's email is not verified, or the token lacks the `email` scope. |
| `401` with "missing the sub/user_id claim" | The token lacks `sub`/`user_id` — usually a missing scope. |
| `401` with "Authentication error: username does not match" | The client-supplied username differs from the derived username (only with `use-email-as-username`). |
| `403 forbidden` after authenticating | Authentication succeeded but the user lacks ACL permission. |
