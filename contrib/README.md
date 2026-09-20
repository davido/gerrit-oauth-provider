# contrib — unsupported helpers

These scripts are **reference/example utilities**, not part of the plugin
runtime and not covered by the plugin's support guarantees.

## `git-credential-google-gerrit`

A reference git credential helper (and token utility) that supplies short-lived
Google OAuth access tokens for Gerrit **Git-over-HTTP**. It runs the browser
OAuth handshake once, caches the refresh token in a `0600` file under
`~/.config` (like git's own `credential-store`), and mints fresh access tokens
on demand.

Use a dedicated Google **Desktop** client whose id is listed in the plugin's
`trusted-audience` (see `Documentation/config-google.md`). Google requires a
`client_secret` even for Desktop clients, but a Desktop secret is **not
confidential** — treat it as distributable. **Never** feed this helper Gerrit's
confidential **Web SSO** secret; Web-client mode is only for a throwaway/test or
operator-controlled client, never production browser-login credentials.

Secret-capable maintained helpers can also work (Git Credential Manager, and
`git-credential-oauth` via `credential.oauthClientSecret`) when configured with
the Desktop client id **and** secret plus Google's endpoints; their default
secretless public-client mode will not (Google rejects it). This script is the
zero-config reference for that flow, plus a scriptable `token` command for
tests/CI.

### One-time setup (per Gerrit host)

`--client-id` is a Google **Desktop** client; `--gerrit-username` is your Gerrit
HTTP username (required for Basic auth):

```bash
git-credential-google-gerrit login \
    --host https://gerrit.example.com \
    --client-id <CLI_CLIENT_ID> \
    --gerrit-username <USERNAME>
```

`login` prompts for the Desktop client secret without echo (never argv, shell
history, or the environment). Secrets (refresh token, client secret) are written
to a `0600` file. Desktop clients accept any `http://localhost:<port>` loopback,
so there is no redirect URI to register.

### Enable for git

The empty first value resets the helper chain so a platform keychain
(`osxkeychain`, `libsecret`, …) does not cache the short-lived token ahead of
this helper:

```bash
git config --global credential.https://gerrit.example.com.helper ""
git config --global --add credential.https://gerrit.example.com.helper google-gerrit
git config --global credential.https://gerrit.example.com.username <USERNAME>
```

`git clone` / `fetch` / `push` then work with no token pasting; the helper
refreshes silently.

### Ad-hoc / test use

```bash
git-credential-google-gerrit token  --host https://gerrit.example.com   # print a fresh token
git-credential-google-gerrit logout --host https://gerrit.example.com   # forget the host
```

Requires Python 3.9+ (standard library only).
