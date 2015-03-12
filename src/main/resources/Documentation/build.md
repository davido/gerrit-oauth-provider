Build
=====

This plugin is built with Buck.

Only in Gerrit tree build mode is supported.

Build in Gerrit tree
--------------------

Clone or link this plugin to the plugins directory of Gerrit's source
tree, and issue the command:

```
  buck build plugins/gerrit-oauth-provider
```

The output is created in

```
  buck-out/gen/plugins/gerrit-oauth-provider/gerrit-oauth-provider.jar
```

This project can be imported into the Eclipse IDE:

```
  ./tools/eclipse/project.py
```
