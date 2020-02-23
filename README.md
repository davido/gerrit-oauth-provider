Gerrit OAuth2 authentication provider
=====================================

[![Build Status](https://travis-ci.org/davido/gerrit-oauth-provider.svg?branch=master)](https://travis-ci.org/davido/gerrit-oauth-provider)


With this plugin Gerrit can use OAuth2 protocol for authentication. 
Supported OAuth providers:

* [AirVantage](https://doc.airvantage.net/av/reference/cloud/API/#API-GeneralInformation-Authentication)
* [Bitbucket](https://confluence.atlassian.com/bitbucket/oauth-on-bitbucket-cloud-238027431.html)
* [CAS](https://www.apereo.org/projects/cas)
* [CoreOS Dex](https://github.com/coreos/dex)
* [Facebook](https://developers.facebook.com/docs/facebook-login)
* [GitHub](https://developer.github.com/v3/oauth/)
* [GitLab](https://about.gitlab.com/)
* [Google](https://developers.google.com/identity/protocols/OAuth2)
* [Keycloak](http://www.keycloak.org/)
* [LemonLDAP::NG](https://lemonldap-ng.org)
* [Office365](https://docs.microsoft.com/en-us/azure/active-directory/develop/active-directory-v2-protocols)

See the [Wiki](https://github.com/davido/gerrit-oauth-provider/wiki) what it can do for you.

Prebuilt artifacts 
------------------

Prebuilt binary artifacts are available on [release page](https://github.com/davido/gerrit-oauth-provider/releases). Make sure to pick the right JAR for your Gerrit version.

Build
-----

To build the plugin with Bazel, install
[Bazel](https://bazel.build/versions/master/docs/install.html) and run the
following:

```
  git clone https://gerrit.googlesource.com/plugins/oauth
  cd oauth && bazel build oauth
```

Install
-------

Copy the `bazel-bin/oauth.jar` to
`$gerrit_site/plugins` and re-run init to configure it:

```
  java -jar gerrit.war init -d <site>
  [...]
  *** OAuth Authentication Provider
  ***
  Use Bitbucket OAuth provider for Gerrit login ? [Y/n]? n
  Use Google OAuth provider for Gerrit login ? [Y/n]?
  Application client id          : <client-id>
  Application client secret      : 
                confirm password : 
  Link to OpenID accounts? [true]: 
  Use GitHub OAuth provider for Gerrit login ? [Y/n]? n
```

Reporting bugs
--------------

Make sure to read the [FAQ](https://github.com/davido/gerrit-oauth-provider/wiki/FAQ) before reporting issues.

License
-------

Apache License 2.0
