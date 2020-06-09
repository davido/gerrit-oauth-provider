workspace(name = "com_github_davido_gerrit_oauth_provider")

load("//:bazlets.bzl", "load_bazlets")

load_bazlets(
    commit = "fff6f20bb2eceaf872a8acf8ad51471c25a82d38",
    #local_path = "/home/<user>/projects/bazlets",
)

# Snapshot Plugin API
#load(
#    "@com_googlesource_gerrit_bazlets//:gerrit_api_maven_local.bzl",
#    "gerrit_api_maven_local",
#)

# Load snapshot Plugin API
#gerrit_api_maven_local()

# Release Plugin API
load(
    "@com_googlesource_gerrit_bazlets//:gerrit_api.bzl",
    "gerrit_api",
)

gerrit_api()

load(":external_plugin_deps.bzl", "external_plugin_deps")

external_plugin_deps(omit_commons_codec = False)
