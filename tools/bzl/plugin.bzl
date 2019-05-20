load(
    "@com_googlesource_gerrit_bazlets//:gerrit_plugin.bzl",
    _gerrit_plugin = "gerrit_plugin",
    _plugin_deps = "PLUGIN_DEPS",
    _plugin_test_deps = "PLUGIN_TEST_DEPS",
)

gerrit_plugin = _gerrit_plugin
PLUGIN_DEPS = _plugin_deps
PLUGIN_TEST_DEPS = _plugin_test_deps
