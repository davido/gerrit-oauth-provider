load("@com_googlesource_gerrit_bazlets//tools:classpath.bzl", "classpath_collector")

classpath_collector(
    name = "main_classpath_collect",
    testonly = 1,
    deps = [
        "//:oauth__plugin",
        "@external_plugin_deps//:com_google_gerrit_gerrit_acceptance_framework",
        "@external_plugin_deps//:com_google_gerrit_gerrit_plugin_api",
    ],
)
