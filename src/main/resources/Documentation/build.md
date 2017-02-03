Build
=====

This plugin is built with Bazel. To install Bazel, follow
the instruction on: https://www.bazel.io/versions/master/docs/install.html.

Two build modes are supported: Standalone and in Gerrit tree.
The standalone build mode is recommended, as this mode doesn't
require the Gerrit tree to exist locally.

### Build standalone

Clone the plugin:

```
  git clone https://gerrit.googlesource.com/plugins/oauth
  cd oauth
```

Issue the command:

```
  bazel build :all
```

The output is created in

```
  bazel-genfiles/@PLUGIN@.jar
```

This project can be imported into the Eclipse IDE:

```
  ./tools/eclipse/project.py
```

### Build in Gerrit tree

Clone or link this plugin to the plugins directory of Gerrit's
source tree, and issue the command:

```
  git clone https://gerrit.googlesource.com/gerrit
  git clone https://gerrit.googlesource.com/plugins/@PLUGIN@
  cd gerrit/plugins
  ln -s ../../@PLUGIN@ .
```

Put the external dependency Bazel build file into the Gerrit /plugins
directory, replacing the existing empty one.

```
  cd gerrit/plugins
  rm external_plugin_deps.bzl
  ln -s @PLUGIN@/external_plugin_deps.bzl .
```

From Gerrit source tree issue the command:

```
  bazel build plugins/@PLUGIN@
```

The output is created in

```
  bazel-genfiles/plugins/@PLUGIN@/@PLUGIN@.jar
```

This project can be imported into the Eclipse IDE.
Add the plugin name to the `CUSTOM_PLUGINS` set in
Gerrit core in `tools/bzl/plugins.bzl`, and execute:

```
  ./tools/eclipse/project.py
```
