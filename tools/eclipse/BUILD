load("//tools/bzl:plugin.bzl", "gerrit_plugin", "PLUGIN_DEPS")
load("//tools/bzl:classpath.bzl", "classpath_collector")

java_library(
    name = "classpath",
    runtime_deps = PLUGIN_DEPS + [
        "//:oauth__plugin",
    ],
)

classpath_collector(
    name = "main_classpath_collect",
    deps = PLUGIN_DEPS + ["//:oauth__plugin"],
)
