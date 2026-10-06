"""Macros for the per-package OAuth provider and test BUILD files."""

load(
    "@com_googlesource_gerrit_bazlets//:gerrit_plugin.bzl",
    "gerrit_plugin",
    "gerrit_plugin_library",
    "gerrit_plugin_tests",
)

_PKG = "com.googlesource.gerrit.plugins.oauth."
_MAIN = "//plugins/oauth/src/main/java/com/googlesource/gerrit/plugins/oauth/"
_NIMBUS = "//plugins/oauth/src/test/java/com/googlesource/gerrit/plugins/oauth:providers_test_deps"

# The shared-core libraries every OAuth artifact compiles against and bundles.
CORE = [_MAIN + pkg for pkg in [
    "base",
    "client",
    "jwt",
    "utils",
]]

def _fqn(name, java_file):
    return _PKG + name + "." + java_file[:-len(".java")]

def oauth_provider(name):
    """Provider library plus its standalone oauth-<name> artifact."""
    module = native.glob(["*PluginModule.java"])
    init = native.glob(["*InitStep.java"])
    gerrit_plugin_library(
        name = name,
        srcs = native.glob(["*.java"], exclude = ["*PluginModule.java"]),
        visibility = ["//plugins/oauth:__subpackages__"],
        deps = CORE,
    )
    gerrit_plugin(
        name = "oauth-" + name,
        srcs = module,
        dir_name = "oauth",
        manifest_entries = [
            "Gerrit-PluginName: gerrit-oauth-provider",
            "Gerrit-Module: " + _fqn(name, module[0]),
            "Gerrit-InitStep: " + _fqn(name, init[0]),
            "Implementation-Title: Gerrit OAuth authentication provider for " + name,
            "Implementation-URL: https://github.com/davido/gerrit-oauth-provider",
        ],
        resources = ["//plugins/oauth:oauth_resources"],
        deps = [":" + name] + CORE,
    )

def oauth_test(name, nimbus = False, lib = True):
    """Co-located test target for a provider or core package."""
    deps = CORE
    if nimbus:
        deps = deps + [_NIMBUS]
    if lib:
        deps = deps + [_MAIN + name]
    gerrit_plugin_tests(
        name = name + "_tests",
        srcs = native.glob(["*.java"]),
        deps = deps,
    )
