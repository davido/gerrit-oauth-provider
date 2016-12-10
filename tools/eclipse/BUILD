load("@com_googlesource_gerrit_bazlets//tools:commons.bzl", "PLUGIN_DEPS")
load("@com_googlesource_gerrit_bazlets//tools:classpath.bzl", "classpath_collector")

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
