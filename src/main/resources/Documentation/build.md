Build
=====

This plugin is built with Bazel.

```
  git clone https://gerrit.googlesource.com/plugins/oauth
  cd oauth
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
