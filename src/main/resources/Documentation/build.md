Build
=====

This plugin is built with Bazel.

```
  git clone https://github.com/davido/gerrit-oauth-provider
  cd gerrit-oauth-provider
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
