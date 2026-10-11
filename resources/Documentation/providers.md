# Provider capability matrix

A per-provider view of the security-relevant features. "Git path" means
Git-over-HTTP token validation (`enable-git-over-http`); browser login (the
OAuth authorization-code flow) is supported by every provider and is not listed.

Every provider's browser login runs on the `HttpOAuthClient`
(JDK + Gson only); see the implementation note below.

| Provider    | Defense-in-depth (Git path)    | User-info mapper   | PKCE | Git-over-HTTP     | Refresh | Revocation | Notes                                                                                                                                    |
|-------------|--------------------------------|--------------------|------|-------------------|---------|------------|------------------------------------------------------------------------------------------------------------------------------------------|
| Google      | `aud`+`azp`, `ev`, `intro`              | inline (by design) | yes  | opt-in, `cache`   | yes    | yes        | opaque `tokeninfo`; `trusted-audience`; `domain` unsupported on Git path                                                                 |
| GitHub      | `app`, `intro`                          | shared             | yes  | opt-in, `cache`\* | n/a    | –          | opaque check-token; needs `client-secret`; GHE via `root-url`                                                                            |
| Discovery   | `jwks`, `aud`+`iss`                     | shared             | yes  | opt-in            | yes    | –          | generic OIDC; JWT bearer; needs `jwks_uri`                                                                                               |
| Keycloak    | `jwks`, `aud`+`iss`                     | shared             | yes  | opt-in            | yes    | yes        | OIDC; rejects the default `account` audience; realm RFC 7009 revoke endpoint                                                             |
| SAP IAS     | `jwks` (id_token)                       | inline             | yes  | on by default     | –      | –          | grandfathered before the opt-in default; also supports the resource-owner password grant                                                 |
| GitLab      | `app`, `scope`, `sub`, `state`, `intro` | shared             | yes  | opt-in, `cache`   | yes    | –          | opaque `token/info` + `/api/v4/user`; `required-scope` (default `read_user`); `trusted-audience`                                         |
| Bitbucket   | –                                       | inline             | yes  | –                 | –      | –          | resource fetch; query-parameter bearer                                                                                                   |
| Azure       | `app`, `jwt`, `intro`, `tid`            | shared             | yes  | opt-in, `cache`\* | yes    | –          | Entra ID; `tenant`; Office365 account-linking alias; Git path introspects Graph `/me` (tokens must be minted by this Gerrit's Azure app) |
| Dex         | –                                       | inline             | yes  | –                 | –      | –          | id_token based; query-parameter bearer                                                                                                   |
| CAS         | –                                       | inline             | yes  | –                 | –      | –          | query-parameter bearer; form or JSON token (`use-json-extractor`); tolerates missing `token_type`                                        |
| AirVantage  | –                                       | inline             | yes  | –                 | –      | –          | query-parameter bearer                                                                                                                   |
| Facebook    | –                                       | inline             | yes  | –                 | –      | –          | request-body client auth                                                                                                                 |
| Phabricator | –                                       | inline             | yes  | –                 | –      | –          | query-parameter bearer                                                                                                                   |

## Legend

Defense-in-depth (Git path) tokens:

- `aud` — token audience must be a trusted client id — confused-deputy defense
  (Google also checks `azp` when present).
- `trusted-audience` — a Git-path option for Google and GitLab (not a separate
  check): it extends the accepted audience set from just `client-id` to
  `client-id` plus configured helper/CI client ids. For Google the token's `aud`
  and `azp`/`issued_to` must be in that set; for GitLab the token's
  `application.uid` must be. It is a policy input to the `aud`/`app` check, not
  an additional layer.
- `app` — token must belong to this Gerrit's OAuth app (GitHub `app.client_id`,
  GitLab `application.uid`, Azure `appid`/`azp`: at least one present and every
  present one equal to `client-id`).
- `jwt` — Azure only: the access token must be a parseable JWT. An opaque or
  unparseable token is rejected up front, because the `app`/`tid` bindings are
  read from its claims — accepting it after the Graph `/me` call would let a
  token minted for another app through (confused-deputy hole).
- `scope` — token must carry the configured `required-scope` (GitLab; default
  `read_user`).
- `sub` — subject binding: the token's subject matches the user-info subject
  (GitLab: `token/info` `resource_owner_id` == `/api/v4/user` id).
- `state` — GitLab only: the account must be `active`; a token for a blocked,
  deactivated, banned, or `ldap_blocked` account is rejected even when otherwise
  valid.
- `iss` — token issuer must match the configured issuer.
- `tid` — Azure tenant binding: the access token's `tid` claim must equal the
  configured fixed tenant (skipped for the multi-tenant aliases
  `organizations`/`common`/`consumers`).
- `ev` — a verified email is required (`email_verified` / `verified_email`).
- `jwks` — the JWT signature is verified against the IdP JWKS with an algorithm
  allowlist (RS\*/ES\*/EdDSA; `HS*`/`none` rejected).
- `intro` — the opaque token is introspected at the IdP (remote call).

Other columns:

- **User-info mapper** — `shared`: a `*UserInfoMapper` reused by the browser and
  Git paths; `inline`: mapped in the service (fine for browser-only providers);
  `inline (by design)`: Google keeps two inline mappings because its `tokeninfo`
  and `userinfo` responses have different shapes.
- **PKCE** — the plugin can send PKCE (RFC 7636) for every provider; opt in per
  provider with `enable-pkce = true`. Default off; enable it when your IdP supports
  or accepts it (a server that does not simply ignores the extra parameters).
- **Git-over-HTTP** — `opt-in`: set `enable-git-over-http = true`; `on by
  default`: SAP IAS is grandfathered on; `–`: not implemented yet.
- **Refresh** — the provider can renew an *expired* access token from its refresh
  token. Renewal is enabled by the provider's `enable-token-refresh` and happens
  on read: browser/REST reads (`GET /accounts/{id}/oauthtoken`) refresh via
  `OAuthTokenRefresher`, and the `oauth-token` SSH command refreshes on demand.
  Neither needs `enable-git-over-http`. Token *delivery* by
  `oauth-token` is provider-agnostic: it prints whichever provider's cached token
  the caller holds — a non-expiring token (e.g. GitHub OAuth-App tokens)
  is printed as-is, no refresh involved. `yes`: refresh supported; `–`: feasible
  but not implemented yet; `n/a`: the provider issues no refreshable token — for
  GitHub, the OAuth-App tokens this plugin mints do not expire and carry no refresh
  token (expiring GitHub-App `ghu_*` user tokens are a separate case; see
  config-github.md).
- **Revocation** — the provider can revoke a token at the IdP (OAuth 2.0 Token
  Revocation, [RFC 7009](https://www.rfc-editor.org/rfc/rfc7009)): a `POST` of the
  token to the IdP's revocation endpoint, through the core
  `OAuthServiceProvider.supportsRevoke()`/`revoke()` extension point. Unlike
  `oauth-token evict`, which only purges Gerrit's cached copy, revocation
  invalidates the token *upstream*. `yes`: implemented — Google
  (`POST https://oauth2.googleapis.com/revoke`) and Keycloak (realm
  `.../protocol/openid-connect/revoke`). RFC 7009 §2.2 mandates HTTP 200 even for
  an unknown/already-invalid token; Google instead returns HTTP 400
  `invalid_token`, which the client tolerates as a successful no-op. `–`: the IdP
  may expose a revocation endpoint but the provider does not implement `revoke()`
  yet.
- `cache` — the provider caches a successful **Git-path** token validation in the
  `oauth_token_validation` cache (keyed by a SHA-256 token hash) so that repeated
  Git operations with the same token do not re-hit the IdP. This is a
  Git-over-HTTP optimization, not a security check: it is the only cache in the
  plugin, and only the four introspection providers (Google, GitHub, GitLab, Azure)
  use it. The browser authorization-code flow never consults it, and the `jwks`
  providers (Keycloak, Discovery, SAP IAS) validate locally and never cache. An
  entry is stored **only when the token or the IdP response carries an expiry**, and
  is then served for min(that expiry, the configured hard TTL; 60 s by default); a
  token with no known expiry is re-validated at the IdP on every request. In
  practice Google (`tokeninfo` `exp`/`expires_in`) and GitLab (`token/info`
  `expires_in`) normally carry one.
- `cache`\* (GitHub, Azure) — the common no-expiry cases. GitHub's classic
  OAuth-App tokens (`gho_*`) carry no `expires_at`, and Azure Graph `/me` returns no
  expiry, so these cache only when the access token is itself a JWT with a parseable
  `exp` — otherwise every request re-validates.

Exactly one provider may have `enable-git-over-http = true` at a time.

**Implementation note:** browser OAuth authorization-code flows run on the
`HttpOAuthClient` (a pure JDK + Gson implementation), and Git-over-HTTP
token validation uses provider-specific HTTP/JWKS validation. The plugin carries
no third-party OAuth or JSON library; `oauth_dependency_allowlist_test` pins the
exact set of runtime jars packaged into the WAR (nimbus for JWT validation and
the SAP cloud-security libraries), failing if an unexpected dependency reappears.

## Future Git-over-HTTP candidates

The providers below support browser login but not Git-over-HTTP token validation
(`enable-git-over-http`, the `–` in the matrix). They are plausible future
candidates, in rough priority order. **Not implemented** — listed so the analysis
is not lost. No work is scheduled.

The gate for each is **confused-deputy defense**: Git-over-HTTP validation must
prove the token was minted for *this* Gerrit's OAuth app (as GitLab does with
`application.uid` and Azure with `appid`), not merely that the token is valid. A
provider is a good candidate only if its token-introspection response exposes a
client/app binding (`client_id` / `aud`); without that, the Git path would accept a
token minted for a different app and the defense-in-depth is weaker than the
existing providers.

- **Facebook** — `debug_token` returns `app_id`, `is_valid`, `user_id`, scopes and
  expiry, then bind to `/me` id. App binding is available, so this is the cleanest.
- **AirVantage** — exposes an RFC 7662 introspection endpoint
  (`/api/oauth/introspect`); a good candidate *if* the response carries the client
  binding. Would bind the introspected subject to `/api/v1/users/current` `uid`.
- **CAS** — modern CAS has `/oauth2.0/introspect` (`active`, `client_id`, `sub`,
  `scope`, `aud`), but CAS deployments vary widely, so support would be best-effort.
- **Dex** — browser id_token validation already works via JWKS; a Git path could
  accept a Dex `id_token` as the password, but that is less standard for credential
  helpers.

Bitbucket and Phabricator have no obvious app-bound introspection surface (`/user`
and `user.whoami` prove token validity, not app binding), so they are not good
candidates for strong Git-over-HTTP defense-in-depth.
