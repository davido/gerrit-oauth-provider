Git-over-HTTP with OIDC Discovery (experimental)
================================================

> **Experimental.** Git-over-HTTP support for the generic OIDC Discovery
> provider is opt-in. Bearer tokens are validated locally against the IdP's
> JWKS. Operational details (log lines, JWKS cache cooldown, claim handling)
> may change between minor versions.

How to push and fetch over HTTPS against a Gerrit instance whose OAuth provider
is a generic OIDC IdP configured through the Discovery provider. The plugin-side
configuration is in [config.md](config.md); this page covers the operator and
end-user workflow once the plugin is configured.

## What the client presents: a JWT whose audience is Gerrit's client-id

On the Git path the plugin validates the presented credential as a JWT against
the IdP's JWKS. It must be a JWT signed by the IdP with:

- **`iss`** equal to the discovery document's `issuer`;
- **`aud`** containing this Gerrit's configured `client-id` — the strict check;
- a **`sub`** claim (used as the Gerrit external id);
- **`exp`** in the future.

The audience is the requirement operators trip over: the token's `aud` must
contain **Gerrit's configured `client-id`**, whether the token is an `id_token`
or an `access_token`. A token minted for a *different* OAuth client — for
example a separate public CLI client used by a credential helper — carries that
other client's id in `aud` and is **rejected**, unless the IdP is configured to
also include Gerrit's `client-id` in that token's audience (a provider-specific
audience mapper / additional-audience setting, as with
[Keycloak](config-keycloak.md)).

See *Token acquisition* below for which token each flow yields.

## Prerequisites — two are provider-specific and important

### 1. The discovery document must expose `jwks_uri`

Local JWT validation needs the IdP's published keys. If the discovery document
exposes no `jwks_uri`, the plugin **refuses to install** the Discovery
Git-over-HTTP login provider and Gerrit fails to start with:

```
The configured discovery document does not expose jwks_uri, so bearer tokens
cannot be JWKS-validated for Git-over-HTTP. Use an OIDC-compliant IdP that
publishes jwks_uri, or set enable-git-over-http = false on the discovery
subsection.
```

If you hit this, either configure your IdP to publish `jwks_uri` (the
OIDC-compliant fix), or set `enable-git-over-http = false` to keep browser-flow
login working without Git-over-HTTP.

### 2. The presented credential must be an IdP-signed JWT

The plugin validates whatever JWT is presented (see above), so any OIDC IdP that
can mint a JWT with Gerrit's `client-id` in the `aud` works — Keycloak, Auth0,
Authentik, Cognito, Okta, Dex, Kanidm, IdentityServer, and so on. Depending on
the flow that JWT is an `id_token` or a JWT `access_token` (see *Token
acquisition*).

IdPs whose *only* Git-usable token would be an **opaque** `access_token` (Google
issues `ya29.*`, GitHub issues `gho_*`) cannot be validated locally and are not
served by the generic Discovery provider. Those need a dedicated provider that
introspects the opaque token remotely; the generic Discovery path is JWT-only.

## Configuration

```
[auth]
  type = OAUTH
  gitBasicAuthPolicy = OAUTH

[plugin "gerrit-oauth-provider-discovery-oauth"]
  root-url = https://idp.example.com/oauth2/openid/gerrit
  client-id = ...
  client-secret = ...
  enable-git-over-http = true
```

- **`auth.type = OAUTH`** is required. With it, Gerrit already defaults
  `gitBasicAuthPolicy` to `OAUTH`, so a Basic-auth password is accepted as an
  OAuth token. The `gitBasicAuthPolicy = OAUTH` line is shown explicitly so a
  deployment that overrides that policy site-wide (e.g. to `HTTP` or `LDAP`) does
  not accidentally route Git/HTTP through password auth instead.
- Git-over-HTTP is **opt-in per provider**; without `enable-git-over-http = true`
  the Discovery login provider is not installed and only browser login works.
- Exactly one OAuth provider may have Git-over-HTTP enabled. If more than one is
  enabled the plugin refuses to start; with only Discovery enabled it is the
  single Git-over-HTTP provider.

### Token refresh

Set `enable-token-refresh = true` (default `false`) to make Discovery
refresh-capable. An expired token is then renewed on read: browser/REST reads
(`GET /accounts/{id}/oauthtoken`) and the core `oauth-token` SSH command both
refresh it via `OAuthTokenRefresher`. It needs only `enable-token-refresh` on
the issuing provider, not `enable-git-over-http`. Neither refreshes during a
Git-over-HTTP request, which validates the presented JWT as-is.

The requested scope stays `openid profile email`; the plugin does **not** add
`offline_access`. Refresh therefore works only if your IdP returns a
refresh_token for the authorization-code flow under that scope. IdPs that issue
a refresh_token solely for `offline_access` will not return one, and refresh
degrades to the current behavior (the token simply expires).

## How validation works on the wire

Every Git-over-HTTP request carries an `Authorization` header:

```
Authorization: Basic <base64("<username>:<jwt>")>
```

or

```
Authorization: Bearer <jwt>
```

The plugin's `DiscoveryOAuthLoginProvider` validates the JWT locally against the
IdP's JWKS (`jwks_uri` from the discovery document, fetched on first validation
and refetched on a `kid` miss with a 60 s cooldown):

- **Signature** against the key whose `kid` matches the header.
- **Algorithm** in the allowlist (`RS*`, `ES*`, `EdDSA`); `HS*` and `none` are
  rejected (algorithm-confusion / unsigned tokens).
- **`iss`** equals the discovery document's `issuer`.
- **`aud`** contains the configured `client-id`.
- **`exp`** in the future; **`nbf`** (if present) not in the future.

The claim mapping then requires **`sub`** (the external id) and reads
`preferred_username`/`username`, `email`, and `name`/`display_name` from the
verified payload to build the Gerrit user. Any failure surfaces to git as `401`.
There is no per-push HTTP call to the IdP and no token-result cache — validation
is local.

## Token acquisition

The credential must be a JWT whose `aud` contains Gerrit's `client-id` (see
above). Which token you use depends on the flow.

### Interactive / authorization-code (developers)

Developers acquire a token with a credential helper (e.g.
`git-credential-oauth`) that runs the OIDC authorization-code flow and caches
the resulting `id_token`. The helper uses its **own public / native IdP client**
— **not** Gerrit's confidential backend client; do not distribute Gerrit's
`client-secret` to developer machines.

Because the helper's client is a different client, its `id_token.aud` is that CLI
client's id, **not** Gerrit's. For the validator to accept it, the IdP must be
configured so the CLI client's token carries **Gerrit's `client-id` in the
`aud`** (a provider-specific additional-audience / audience-mapper setting).
Request the `openid profile email` scopes so the token carries `sub`,
`preferred_username`, `email` and `name`. Present the `id_token` as the Git
password.

### Service account / client credentials (CI, automation)

The `client_credentials` grant returns an **`access_token` only — there is no
`id_token`** (RFC 6749 §4.4.3). It can be used for Git-over-HTTP **only if** the
IdP issues **JWT** access tokens and stamps Gerrit's `client-id` into the `aud`,
and the service account carries `sub` plus the mapped `profile`/`email` claims:

```bash
TOKEN=$(curl -sS -X POST \
  -d grant_type=client_credentials \
  -d client_id=<gerrit-client-id> \
  -d client_secret=<client-secret> \
  -d scope="openid profile email" \
  https://idp.example.com/oauth2/token | jq -r .access_token)
```

If the IdP issues opaque access tokens, or does not put Gerrit's `client-id` in
the `aud`, this flow cannot be used. Verify the token before relying on it (see
*Verifying the token* below).

### Using the token

Present it without putting it in the URL — a token in the URL leaks into shell
history, `ps`, and git trace logs:

```bash
git -c http.extraHeader="Authorization: Bearer $TOKEN" \
  ls-remote -h https://gerrit.example.com/a/myproject
```

Both `id_token`s and access tokens are short-lived, so acquire a fresh one per
job or refresh before expiry.

## Verifying the token without git

Decode the JWT payload (base64url, often unpadded) to check its claims — use a
base64url-safe decoder rather than `base64 -d`, which differs across
macOS/Linux and rejects unpadded input:

```bash
python3 -c 'import sys,base64,json; p=sys.argv[1].split(".")[1]; p+="="*(-len(p)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(p)),indent=2))' "$TOKEN"
```

Confirm `iss` matches the discovery `issuer`, `aud` contains your `client-id`,
`sub` is present, and `exp` is in the future.

## Migrating a provider-specific OIDC wrapper to Discovery (experimental)

Auth0, Authentik, Cognito, Tuleap and LemonLDAP were previously shipped as
dedicated wrappers; they are generic OIDC providers and have been **removed** in
favour of this Discovery provider. The blocker when migrating is **account
identity**: Gerrit stores each user's external id as `<scheme>:<value>`, and
Discovery's default scheme is `discovery-oauth`, whereas the wrappers used their
own (`auth0-oauth`, `tuleap-oauth`, `llng-oauth`, ...). Switching provider without
preserving the scheme would change every external id and **unlink all accounts**.

**Limitation — one Discovery instance:** the plugin registers a single Discovery
provider, configured by one `gerrit-oauth-provider-discovery-oauth` section. It can
therefore replace **one** removed wrapper per Gerrit site. A site that ran two or
more of the removed wrappers at the same time (e.g. Auth0 and Cognito as separate
login options) cannot preserve all of them through Discovery — only one can be
migrated. Multi-instance Discovery aliases are not currently supported; such a site
needs a separate plan.

Use `external-id-scheme` to keep the old scheme:

- **`external-id-scheme`** (default `discovery-oauth`) — the scheme Discovery
  emits for external ids. Set it to the wrapper's scheme to migrate without
  relinking. Restricted to an allowlist (the default plus the consolidation
  wrapper schemes `auth0-oauth`, `authentik-oauth`, `cognito-oauth`,
  `tuleap-oauth`, `llng-oauth`); any other value fails startup.
- **`link-to-existing-gerrit-accounts`** (default `false`) — when `true`, the
  browser flow sets a `gerrit:<username>` claimed identity, so a first OAuth login
  links to an existing account carrying that username external id (matching the
  Authentik/Cognito wrappers). Requires `preferred_username`/`username` in the
  response: the **browser login fails closed** (401) if the flag is set but no
  username is present, so linking is never silently skipped. Browser-flow only;
  the Git path uses the base mapping (no claimed identity) and is unaffected by
  this flag.
- **`client-auth-method`** (default `basic`) — how Discovery authenticates at the
  token endpoint: `basic` (HTTP Basic per RFC 6749 §2.3.1) or `request-body`
  (`client_id`/`client_secret` in the POST body). Set `request-body` for IdPs that
  require it (e.g. a default LemonLDAP::NG deployment); any other value fails
  startup.

**This knob preserves only the external-id scheme** (`<scheme>:<sub>`), not the
claim the id is built from, nor any account-linking behavior. So per candidate:

- **Auth0, Tuleap** — identity shape covered by the scheme knob (both map from
  `sub`; their raw-`<sub>` claimed identity is a no-op, see the note under the
  Auth0 recipe). Allowlisted. Only Auth0 has a worked recipe/proof below; give
  Tuleap the same before relying on it.
- **Authentik, Cognito** — the scheme knob preserves their external id
  (`<scheme>:<sub>`), and Discovery now honors `link-to-existing-gerrit-accounts`
  (emitting the same `gerrit:<username>` claimed identity on the browser flow), so
  both are **allowlisted and migratable**. Set `link-to-existing-gerrit-accounts =
  true` on the Discovery subsection if the wrapper had it enabled.
- **LemonLDAP** — the scheme knob preserves its external id (`llng-oauth:<sub>`,
  from `sub`; its claimed identity is already `null`), and its request-body client
  authentication is now covered by `client-auth-method = request-body`. So it is
  **allowlisted and migratable**; see the LemonLDAP recipe below.
- **Dex** — maps its external id from `email`, not `sub`, so scheme preservation
  alone would still relink (`dex-oauth:<sub>` != the existing `dex-oauth:<email>`).
  It is **not allowlisted**; it needs a separate `external-id-claim = email` knob
  first.

**Scope — login identities only.** `external-id-scheme` changes the OAuth **login**
external id (browser and Git-over-HTTP, `<scheme>:<sub>`). It does **not** change
the external id that Gerrit's account-creation path (`AccountExternalIdCreator` --
REST create-account and pre-provisioning) writes for the Discovery provider, which
stays `discovery-oauth:<username>`. Existing accounts are unaffected (stored
external ids are never rewritten). But a deployment that relies on the *old*
provider's account-creation scheme (e.g. `auth0-oauth:<username>`) would need that
path wired to the override too -- a follow-up, out of scope for this proof of
concept.

### Auth0 recipe

Auth0 is the cleanest case: its external id is `auth0-oauth:<sub>` and Discovery
also maps the external id from `sub`, so only the scheme must be preserved.

Reuse the Auth0 `root-url` (its issuer/domain) unchanged. Auth0 publishes OIDC
discovery at `<root-url>/.well-known/openid-configuration`, whose `issuer` is
`https://<tenant>.auth0.com/` (with a trailing slash) and which exposes
`jwks_uri`, `authorization_endpoint`, `token_endpoint`, and `userinfo_endpoint`.
Discovery reads all of these from the document, so the trailing-slash issuer and
the JWKS URL are picked up automatically — nothing is hand-constructed:

```
[plugin "gerrit-oauth-provider-discovery-oauth"]
  root-url           = https://<tenant>.auth0.com   # same as the Auth0 wrapper's root-url
  client-id          = <client-id>
  client-secret      = <client-secret>
  external-id-scheme = auth0-oauth                   # preserve the auth0-oauth:<sub> external id
```

Discovery requests `openid profile email` (as the Auth0 wrapper does), maps the
external id from `sub`, and reads `preferred_username`/`email`/`name`.

What this preserves:

- **Discovery from the documented root-url** — `<root-url>/.well-known/...` returns
  the issuer / `jwks_uri` / endpoints Discovery needs, so the same `root-url` the
  Auth0 wrapper used works unchanged.
- **External id** — stays `auth0-oauth:<sub>` (Auth0 and Discovery both map from
  `sub`; the scheme override keeps the `auth0-oauth` prefix).
- **Browser and Git-over-HTTP** — both honor `external-id-scheme`: Discovery
  resolves the scheme once and the Git-path validator reuses the same mapper, so a
  user gets `auth0-oauth:<sub>` on either path.
- **Claimed identity** — the one behavioral difference, and it is harmless (below).

**Note — claimed identity (verified immaterial).** The Auth0 wrapper passes the
raw `<sub>` as the `OAuthUserInfo` *claimed identity*; Discovery leaves it `null`.
This difference does not matter: Gerrit's `OAuthSession` looks the claimed value
up as an external id, and a raw `<sub>` parses to a *schemeless* key that matches
no registered external id (OAuth accounts are stored under `auth0-oauth:<sub>`),
so the account-linking branch never fires -- it is a no-op. And with
`external-id-scheme = auth0-oauth` the OAuth external id is unchanged, so existing
users match their account directly. **No claimed-identity option is needed for
Auth0.**

By contrast, Authentik and Cognito pass `gerrit:<username>` as claimed identity
when `link-to-existing-gerrit-accounts` is set -- that *does* resolve and links
accounts. Discovery now expresses this via its own `link-to-existing-gerrit-accounts`
option (see the Authentik recipe below); Auth0 does not need it.

### Authentik recipe

Authentik's external id is `authentik-oauth:<sub>` (mapped from `sub`), and -- when
`link-to-existing-gerrit-accounts` was enabled on the wrapper -- it also sets a
`gerrit:<preferred_username>` claimed identity. Both are preservable, but two
things differ from Auth0.

**Root URL is per application.** The Authentik wrapper used the base Authentik URL
with shared `/application/o/...` endpoints. Discovery instead reads OIDC metadata,
and Authentik publishes discovery **per OAuth application** at
`<base>/application/o/<app-slug>/.well-known/openid-configuration` (issuer
`<base>/application/o/<app-slug>/`). So point Discovery's `root-url` at the
application's issuer path -- `<app-slug>` is the application slug in the Authentik
admin:

```
[plugin "gerrit-oauth-provider-discovery-oauth"]
  root-url                         = https://authentik.example.com/application/o/<app-slug>
  client-id                        = <client-id>       # the same Authentik OAuth app as the wrapper
  client-secret                    = <client-secret>
  external-id-scheme               = authentik-oauth    # preserve authentik-oauth:<sub>
  link-to-existing-gerrit-accounts = true               # only if the wrapper had it enabled
```

What this preserves:

- **External id** -- stays `authentik-oauth:<sub>`. Use the **same Authentik OAuth
  application** (same `client-id`) as the wrapper so `sub` is identical; the scheme
  override keeps the `authentik-oauth` prefix. Browser and Git paths agree.
- **Account linking** -- set `link-to-existing-gerrit-accounts = true` **only if**
  the wrapper had it; Discovery then emits the same `gerrit:<preferred_username>`
  claimed identity on the browser flow. If the wrapper had it off, leave it off.
  (The browser login fails closed if it is on but `preferred_username` is absent --
  the wrapper had the same effective requirement.)

Caveat: the account-creation path is not covered (see "Scope" above); a deployment
relying on the wrapper's account-creation external ids should not migrate yet.

### Cognito recipe

Cognito's external id is `cognito-oauth:<sub>` (mapped from `sub`), and -- when
`link-to-existing-gerrit-accounts` was enabled -- it sets a
`gerrit:<preferred_username>` claimed identity. The error-prone part is the URL.
The Cognito wrapper's `root-url` is the **hosted auth domain**
(`https://<domain>.auth.<region>.amazoncognito.com`, or a custom domain), where
`/oauth2/authorize`, `/oauth2/token`, and `/oauth2/userInfo` live. But Cognito
publishes OIDC **discovery on the user-pool issuer**, not the hosted domain:
`https://cognito-idp.<region>.amazonaws.com/<userPoolId>/.well-known/openid-configuration`.

So point Discovery's `root-url` at the **issuer** (the user-pool URL), *not* the
hosted domain. Discovery reads the discovery document, which points its
`authorization_endpoint`/`token_endpoint` back at the hosted domain and its
`jwks_uri` at the issuer, so every endpoint still resolves correctly:

```
[plugin "gerrit-oauth-provider-discovery-oauth"]
  root-url                         = https://cognito-idp.<region>.amazonaws.com/<userPoolId>
  client-id                        = <client-id>       # the same Cognito app client as the wrapper
  client-secret                    = <client-secret>
  external-id-scheme               = cognito-oauth      # preserve cognito-oauth:<sub>
  link-to-existing-gerrit-accounts = true              # only if the wrapper had it enabled
```

What this preserves:

- **External id** -- stays `cognito-oauth:<sub>`. Use the **same Cognito app
  client** so `sub` is identical; the scheme override keeps the prefix. Browser and
  Git paths agree.
- **Account linking** -- set `link-to-existing-gerrit-accounts = true` only if the
  wrapper had it; Discovery emits the same `gerrit:<preferred_username>` claimed
  identity on the browser flow. Cognito returns `preferred_username` only when the
  user has that attribute: if linking is on but it is absent, the browser login
  fails closed (the wrapper NPE'd in the same case, so this is stricter but safer).

Caveat: the account-creation path is not covered (see "Scope" above).

### Tuleap recipe

Tuleap is a clean case, like Auth0: its external id is `tuleap-oauth:<sub>` (mapped
from `sub`), and its raw-`<sub>` claimed identity is a **no-op** (see the Auth0
note above -- a schemeless `<sub>` matches no registered external id). Tuleap uses
default HTTP Basic client auth (no request-body gap) and publishes OIDC discovery
at `<root-url>/.well-known/openid-configuration`, with authorize/token/userinfo
under `<root-url>/oauth2/...` -- the same base URL the wrapper used. So reuse the
Tuleap `root-url` unchanged:

```
[plugin "gerrit-oauth-provider-discovery-oauth"]
  root-url           = https://tuleap.example.com   # same as the Tuleap wrapper's root-url
  client-id          = <client-id>
  client-secret      = <client-secret>
  external-id-scheme = tuleap-oauth                  # preserve tuleap-oauth:<sub>
```

What this preserves: the external id stays `tuleap-oauth:<sub>` (use the same OAuth
app so `sub` is identical; the scheme override keeps the prefix) on both the
browser and Git paths. No `link-to-existing-gerrit-accounts` is needed -- Tuleap's
claimed identity is a no-op. Same account-creation scope caveat as Auth0.

### LemonLDAP recipe

LemonLDAP::NG is a clean scheme-preserve: its external id is `llng-oauth:<sub>`
(mapped from `sub`) and it never sets a claimed identity, so no
`link-to-existing-gerrit-accounts` is needed. The one difference from Tuleap is
client authentication -- a default LemonLDAP::NG deployment expects the client
credentials in the request body, not HTTP Basic -- which
`client-auth-method = request-body` now covers. LemonLDAP serves OIDC discovery at
`<root-url>/.well-known/openid-configuration`, with authorize/token/userinfo under
`<root-url>/oauth2/...` -- the same base URL the wrapper used. So reuse the
LemonLDAP `root-url` unchanged:

```
[plugin "gerrit-oauth-provider-discovery-oauth"]
  root-url           = https://auth.example.com   # same as the LemonLDAP wrapper's root-url
  client-id          = <client-id>
  client-secret      = <client-secret>
  external-id-scheme = llng-oauth                  # preserve llng-oauth:<sub>
  client-auth-method = request-body                # LemonLDAP::NG default token-endpoint auth
```

What this preserves: the external id stays `llng-oauth:<sub>` (same OAuth app so
`sub` is identical; the scheme override keeps the prefix) on both the browser and
Git paths. Same account-creation scope caveat as Auth0. If your LemonLDAP is
configured to accept HTTP Basic instead, drop `client-auth-method` (it defaults to
`basic`).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Startup error "does not expose jwks_uri" | The configured IdP's discovery document has no `jwks_uri`. Use an OIDC-compliant IdP or set `enable-git-over-http = false`. |
| `401` with "malformed JWT" | The presented credential is an opaque token, not a JWT. Present an IdP-signed JWT (an `id_token`, or a JWT `access_token`) — not an opaque `access_token`. |
| `401` with "JWT validation failed" and audience | The token's `aud` does not contain Gerrit's configured `client-id` — usually because it was minted for a different OAuth client (e.g. a separate CLI client). Use a token issued for Gerrit's client, or configure the IdP to add Gerrit's `client-id` to that token's audience. |
| `401` with "JWT issuer not accepted" | The token's `iss` does not match the discovery document's `issuer`. Re-fetch the discovery document and confirm it matches. |
| `401` with "Response doesn't contain sub field" | The IdP omitted `sub` from the token. Check the IdP's scope/claim configuration. |
| `401` with "Authentication error: username does not match" | The client-supplied username differs from the token's `preferred_username`. |
| `403 forbidden` after authenticating | Authentication succeeded but the user lacks ACL permission. |
