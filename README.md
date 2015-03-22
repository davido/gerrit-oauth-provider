Gerrit OAuth2 authentication provider
=====================================

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
  git pull -recursive https://github.com/davido/gerrit-oauth-provider.git
  cd gerrit-oauth-provider && buck build plugin
```

License
-------

Apache License 2.0
