Build
=====

This plugin is built with Buck.

Two build modes are supported: Standalone and in Gerrit tree.
The standalone build mode is recommended, as this mode doesn't require
the Gerrit tree to exist locally.

Build in Standalone mode
------------------------

```
  git clone --recursive https://github.com/davido/gerrit-oauth-provider
  cd gerrit-oauth-provider
  buck build plugin
```

The output is created in

```
  buck-out/gen/@PLUGIN@.jar
```

Build in Gerrit tree
--------------------

Clone or link this plugin to the plugins directory of Gerrit's source
tree, and issue the command:

```
  buck build plugins/gerrit-oauth-provider
```

The output is created in

```
  buck-out/gen/plugins/@PLUGIN@/@PLUGIN@.jar
```

This project can be imported into the Eclipse IDE:

```
  ./tools/eclipse/project.py
```
