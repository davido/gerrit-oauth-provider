load("//tools/bzl:junit.bzl", "junit_tests")
load(
    "//tools/bzl:plugin.bzl",
    "gerrit_plugin",
    "PLUGIN_DEPS",
)

gerrit_plugin(
    name = "oauth",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: gerrit-oauth-provider",
        "Gerrit-Module: com.googlesource.gerrit.plugins.oauth.Module",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.oauth.HttpModule",
        "Gerrit-InitStep: com.googlesource.gerrit.plugins.oauth.InitOAuth",
        "Implementation-Title: Gerrit OAuth authentication provider",
        "Implementation-URL: https://github.com/davido/gerrit-oauth-provider",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "@commons_codec//jar:neverlink",
        "@scribe//jar",
    ],
)

junit_tests(
    name = "oauth_tests",
    srcs = glob(["src/test/java/**/*.java"]),
    tags = ["oauth"],
    deps = PLUGIN_DEPS + [
        ":oauth__plugin",
        "@scribe//jar",
    ],
)
