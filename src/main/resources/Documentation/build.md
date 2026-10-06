Build
=====

This plugin is built with Bazel. To install Bazel, follow
the instruction on: https://www.bazel.io/versions/master/docs/install.html.

Two build modes are supported: Standalone and in Gerrit tree.
The standalone build mode is recommended, as this mode doesn't
require the Gerrit tree to exist locally.

### Packaged runtime JAR allowlist test

This plugin tracks the set of third-party runtime JARs that are bundled into the plugin JAR.
A deterministic, version-agnostic manifest is generated from the plugin’s runtime classpath and
compared against the checked-in allowlist:

`oauth_third_party_runtime_jars.allowlist.txt`

This acts as a guardrail to detect unintended changes to the packaged runtime dependency set.

To run the check:

```bash
# Standalone plugin build:
bazelisk test //:oauth_dependency_allowlist_test
# In the Gerrit tree:
bazelisk test //plugins/oauth:oauth_dependency_allowlist_test
```

Note: this test is not part of the `oauth_tests` suite, so `bazel test
//plugins/oauth/...` (all targets) is the way to run it alongside the unit tests.

#### Updating the allowlist

If the test fails because the packaged third-party JAR set changed, the plugin’s bundled runtime
dependencies have changed.

If the change is expected and has been reviewed, refresh the allowlist from the generated manifest:

```bash
# Standalone plugin build:
bazelisk build //:oauth_dependency_allowlist_test_manifest
cp bazel-bin/oauth_dependency_allowlist_test_manifest.txt oauth_third_party_runtime_jars.allowlist.txt

# In the Gerrit tree:
bazelisk build //plugins/oauth:oauth_dependency_allowlist_test_manifest
cp bazel-bin/plugins/oauth/oauth_dependency_allowlist_test_manifest.txt plugins/oauth/oauth_third_party_runtime_jars.allowlist.txt
```

Commit the updated allowlist along with the dependency change.

### Build standalone

Clone the plugin:

```
  git clone https://github.com/davido/gerrit-oauth-provider
  cd gerrit-oauth-provider
```

Issue the command:

```
  bazel build :all
```

The output is created in

```
  bazel-bin/@PLUGIN@.jar
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

Then link the plugin's module fragment into Gerrit's `plugins`
directory, replacing the placeholder file provided by Gerrit.
This fragment exposes the plugin's Bazel module and its external
dependencies to the Gerrit root module when building in-tree.

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
```

This project can be imported into the Eclipse IDE.
Add the plugin name to the `CUSTOM_PLUGINS` set in
Gerrit core in `tools/bzl/plugins.bzl`, and execute:

```
  ./tools/eclipse/project.py
```

### Single-provider artifacts

Besides the all-inclusive `@PLUGIN@.jar`, the plugin can be built as slimmer,
single-provider artifacts that bundle only one provider plus the shared core
libraries. They are optional and do not affect the default build:

```
  bazel build @PLUGIN@-google @PLUGIN@-github @PLUGIN@-discovery @PLUGIN@-keycloak @PLUGIN@-sapias
```

(prefix the targets with `plugins/@PLUGIN@:` when building in the Gerrit tree).
Each artifact registers as the same `gerrit-oauth-provider` plugin and ships its
own init step, so `gerrit.config` and `init` behave exactly as with the full
`@PLUGIN@.jar` — deploy only the provider you actually use.

SAP IAS is available only from the standalone `@PLUGIN@-sapias` artifact.

### Gerrit-tree-only plugin checks

This plugin contains additional guardrail tests that are meaningful only
when it is built inside the Gerrit source tree (e.g. checks comparing the
plugin’s packaged runtime jars against Gerrit’s own runtime classpath).

These tests are intentionally skipped in standalone plugin builds.

To run them, ensure the following is set in Gerrit's `.bazelrc`:

```
common --@com_googlesource_gerrit_bazlets//flags:in_gerrit_tree=true
```

Then execute:

```
bazel test plugins/@PLUGIN@:oauth_no_overlap_with_gerrit
```

Standalone plugin workspaces should not set this flag. In that case, the
corresponding targets are marked incompatible and reported as SKIPPED by Bazel.

[Back to @PLUGIN@ documentation index][index]

[index]: index.html
