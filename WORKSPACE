workspace(name = "com_github_davido_gerrit_oauth_provider")

load("//:bazlets.bzl", "load_bazlets")

load_bazlets(
    commit = "7a9ae377b519934c87184cc05845663ed708b69c",
    #local_path = "/home/<user>/projects/bazlets",
)

load(
    "@com_googlesource_gerrit_bazlets//:gerrit_api.bzl",
    "gerrit_api",
)

gerrit_api()

load(":external_plugin_deps.bzl", "external_plugin_deps")

external_plugin_deps(omit_commons_codec = False)
