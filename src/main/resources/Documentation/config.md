Configuration
=============

The configuration of the @PLUGIN@ plugin is done in the `gerrit.config`
file. `auth.type` must be set to `OAUTH`:

```
[auth]
  type = OAUTH
```

Google and GitHub specific providers are configured under @PLUGIN@ section,
appended with provider suffix: `-google-oauth` and `-github-oauth`:

```
  [plugin "@PLUGIN@-google-oauth"]
    client-id = "<client-id>"
    client-secret = "<client-secret>"
    link-to-existing-openid-accounts = true

  [plugin "@PLUGIN@-github-oauth"]
    client-id = "<client-id>"
    client-secret = "<client-secret>"
```

When one from the sections above is omitted, OAuth SSO is used.
The login form with provider selection isn’t shown. When both
sections are omitted, Gerrit will not start.

Google OAuth provider seamlessly supports linking of OAuth identity
to existing OpenID accounts. This feature is deactivated by default.
To activate it, add

```
plugin.gerrit-oauth-provider-google-oauth.link-to-existing-openid-accounts = true
```

to Google OAuth configuration section.

