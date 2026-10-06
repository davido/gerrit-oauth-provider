Build
=====

This plugin is built with Bazel in the Gerrit source tree.

Clone or link this plugin into the `plugins` directory of the Gerrit
source tree. Then link the plugin's module fragment into Gerrit's
`plugins` directory, replacing the placeholder file. This fragment
exposes the plugin's Bazel module and its external dependencies to
the Gerrit root module when building in-tree.

```
  cd gerrit/plugins
  rm external_plugin_deps.MODULE.bazel
  ln -s @PLUGIN@/external_plugin_deps.MODULE.bazel .
```

From the Gerrit source tree run:

```
  bazel build plugins/@PLUGIN@
```

The output is created in:

```
  bazel-bin/plugins/@PLUGIN@/@PLUGIN@.jar
```

To execute the tests run either of:

```
  bazel test plugins/@PLUGIN@/...
  bazel test --test_tag_filters=@PLUGIN@ //...
  bazel test plugins/@PLUGIN@:@PLUGIN@_plugin_tests
```

This project can also be imported into the Eclipse IDE. Add the plugin name
to the `CUSTOM_PLUGINS` set in Gerrit core in `tools/bzl/plugins.bzl`, and
execute:

```
  ./tools/eclipse/project.py
```

### Single-provider artifacts

Besides the default `@PLUGIN@.jar`, the plugin can be built as slimmer,
single-provider artifacts that bundle only one provider plus the shared core
libraries. They are optional and do not affect the default build:

```
  bazel build \
    plugins/@PLUGIN@:@PLUGIN@-airvantage \
    plugins/@PLUGIN@:@PLUGIN@-azure \
    plugins/@PLUGIN@:@PLUGIN@-bitbucket \
    plugins/@PLUGIN@:@PLUGIN@-cas \
    plugins/@PLUGIN@:@PLUGIN@-dex \
    plugins/@PLUGIN@:@PLUGIN@-discovery \
    plugins/@PLUGIN@:@PLUGIN@-facebook \
    plugins/@PLUGIN@:@PLUGIN@-github \
    plugins/@PLUGIN@:@PLUGIN@-gitlab \
    plugins/@PLUGIN@:@PLUGIN@-google \
    plugins/@PLUGIN@:@PLUGIN@-keycloak \
    plugins/@PLUGIN@:@PLUGIN@-phabricator \
    plugins/@PLUGIN@:@PLUGIN@-sapias
```

Each artifact registers as the same `gerrit-oauth-provider` plugin and ships
its own init step, so `gerrit.config` and `init` behave exactly as with the
full `@PLUGIN@.jar` — deploy only the provider you actually use.

SAP IAS is available only from the `@PLUGIN@-sapias` artifact.

### Packaged runtime JAR allowlist test

This plugin tracks the set of third-party runtime JARs that are bundled into the plugin JAR.
A deterministic, version-agnostic manifest is generated from the plugin’s runtime classpath and
compared against the checked-in allowlist:

`oauth_third_party_runtime_jars.allowlist.txt`

This acts as a guardrail to detect unintended changes to the packaged runtime dependency set.

To run the check:

```bash
  bazel test plugins/@PLUGIN@:oauth_dependency_allowlist_test
```

#### Updating the allowlist

If the test fails because the packaged third-party JAR set changed, the plugin’s bundled runtime
dependencies have changed.

If the change is expected and has been reviewed, refresh the allowlist:

```bash
  bazel build plugins/@PLUGIN@:oauth_dependency_allowlist_test_manifest
  cp bazel-bin/plugins/@PLUGIN@/oauth_dependency_allowlist_test_manifest.txt \
  plugins/@PLUGIN@/oauth_third_party_runtime_jars.allowlist.txt
```

Commit the updated allowlist along with the dependency change.

### Gerrit-tree-only plugin checks

This plugin contains additional guardrail tests that are meaningful only
when it is built inside the Gerrit source tree (e.g. checks comparing the
plugin’s packaged runtime jars against Gerrit’s own runtime classpath).

Then execute:

```bash
  bazel test plugins/@PLUGIN@:oauth_dependency_overlap_test
```

[Back to @PLUGIN@ documentation index][index]

[index]: index.html
