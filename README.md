OAuth2 authentication provider for Gerrit Code Review
=====================================================

This plugin depends on small extensions to supported OAuth
authentication point, that still pending for review. To build
the plugin against stable-2.10 Gerrit tree, these changes must
be cherry-picked: [1].

Supported OAuth providers:
--------------------------

* GitHub OAuth2
* Google OAuth2

Linking OAuth identity to existing Gerrit accounts:
---------------------------------------------------

As of April 20, 2015 Google shuts down its deprecated OpenID 2.0
provider. To simplify the migration google-oauth-povider plugin
seamlessly supports linking of OAuth identity to existing Gerrit
accounts. To activate it, add the following configuration option
to plugin config section:

```
[plugin "gerrit-oauth-provider-google-oauth"]
    link-to-existing-openid-accounts = true
```

* [1] https://gerrit-review.googlesource.com/#/q/status:open+project:gerrit+branch:stable-2.10+topic:oauth-authentication-provider
