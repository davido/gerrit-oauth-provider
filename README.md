Gerrit OAuth2 authentication provider
=====================================

[![Build Status](https://travis-ci.org/davido/gerrit-oauth-provider.svg?branch=master)](https://travis-ci.org/davido/gerrit-oauth-provider)


With this plugin Gerrit can use OAuth2 protocol for authentication. 
Supported OAuth providers:

* GitHub
* Google

See the [Wiki](https://github.com/davido/gerrit-oauth-provider/wiki) what it can do for you.

Installation
------------

To build the plugin, install [Buck](http://facebook.github.io/buck/setup/install.html)
and run the following:

```
  git clone --recursive https://github.com/davido/gerrit-oauth-provider.git
  cd gerrit-oauth-provider && buck build plugin
```

Copy the `buck-out/gen/gerrit-oauth-provider.jar` to
`$gerit_site/plugins` and re-run init to configure it:

```
  java -jar gerrit.war init -d <site>
  [...]
  *** OAuth Authentication Provider
  ***
  Use Google OAuth provider for Gerrit login ? [Y/n]?
  Application client id          : <client-id>
  Application client secret      : 
                confirm password : 
  Link to OpenID accounts? [true]: 
  Use GitHub OAuth provider for Gerrit login ? [Y/n]? n
``` 

License
-------

Apache License 2.0
