load("//tools/bzl:plugin.bzl", "PLUGIN_DEPS", "gerrit_plugin")
load("//tools/bzl:classpath.bzl", "classpath_collector")

java_library(
    name = "classpath",
    runtime_deps = PLUGIN_DEPS + [
        "//:gerrit-oauth-provider__plugin",
    ],
)

classpath_collector(
    name = "main_classpath_collect",
    deps = PLUGIN_DEPS + ["//:gerrit-oauth-provider__plugin"],
)
