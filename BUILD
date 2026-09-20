load(
    "@com_googlesource_gerrit_bazlets//:gerrit_plugin.bzl",
    "gerrit_plugin",
    "gerrit_plugin_ext_test_deps",
    "gerrit_plugin_library",
    "gerrit_plugin_tests",
)

EXT_DEPS = [
    "com.github.scribejava:scribejava-apis",
    "com.github.scribejava:scribejava-core",
    "com.nimbusds:nimbus-jose-jwt",
    "com.sap.cloud.security.java:api",
    "com.sap.cloud.security.java:security",
]

PLUGIN = "oauth"

UTILS_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/utils/**/*.java"

gerrit_plugin_library(
    name = "utils",
    srcs = glob([UTILS_SRCS]),
    visibility = ["//visibility:public"],
)

CLIENT_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/client/**/*.java"

gerrit_plugin_library(
    name = "client",
    srcs = glob([CLIENT_SRCS]),
    visibility = ["//visibility:public"],
    deps = [":utils"],
)

BASE_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/base/**/*.java"

gerrit_plugin_library(
    name = "base",
    srcs = glob([BASE_SRCS]),
    visibility = ["//visibility:public"],
    deps = [
        ":client",
        ":utils",
    ],
)

JWT_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/jwt/**/*.java"

gerrit_plugin_library(
    name = "jwt",
    srcs = glob([JWT_SRCS]),
    ext_deps = ["com.nimbusds:nimbus-jose-jwt"],
    plugin = PLUGIN,
    visibility = ["//visibility:public"],
    deps = [":utils"],
)

PROVIDERS_NATIVE_SRCS = [
    "src/main/java/com/googlesource/gerrit/plugins/oauth/airvantage/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/azure/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/bitbucket/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/cas/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/dex/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/discovery/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/facebook/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/github/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/gitlab/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/google/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/keycloak/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/phabricator/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/sap/**/*.java",
]

gerrit_plugin_library(
    name = "providers-native",
    srcs = glob(PROVIDERS_NATIVE_SRCS),
    ext_deps = [
        "com.sap.cloud.security.java:api",
        "com.sap.cloud.security.java:security",
        "com.sap.cloud.security:env",
        "com.sap.cloud.security.xsuaa:token-client",
    ],
    plugin = PLUGIN,
    visibility = ["//visibility:public"],
    deps = [
        ":base",
        ":client",
        ":jwt",
        ":utils",
    ],
)

gerrit_plugin(
    srcs = glob(
        ["src/main/java/**/*.java"],
        exclude = [
            BASE_SRCS,
            CLIENT_SRCS,
            JWT_SRCS,
            UTILS_SRCS,
        ] + PROVIDERS_NATIVE_SRCS,
    ),
    ext_deps = [
        "com.fasterxml.jackson.core:jackson-databind",
        "com.sap.cloud.security:env",
        "com.sap.cloud.security.xsuaa:token-client",
    ] + EXT_DEPS,
    manifest_entries = [
        "Gerrit-PluginName: gerrit-oauth-provider",
        "Gerrit-Module: com.googlesource.gerrit.plugins.oauth.Module",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.oauth.HttpModule",
        "Gerrit-InitStep: com.googlesource.gerrit.plugins.oauth.InitOAuth",
        "Implementation-Title: Gerrit OAuth authentication provider",
        "Implementation-URL: https://github.com/davido/gerrit-oauth-provider",
    ],
    plugin = PLUGIN,
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        ":base",
        ":client",
        ":jwt",
        ":providers-native",
        ":utils",
    ],
)

PROVIDERS_NATIVE_TEST_SRCS = [
    "src/test/java/com/googlesource/gerrit/plugins/oauth/airvantage/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/azure/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/bitbucket/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/cas/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/dex/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/discovery/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/facebook/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/github/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/gitlab/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/google/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/keycloak/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/phabricator/**/*.java",
    "src/test/java/com/googlesource/gerrit/plugins/oauth/sap/**/*.java",
]

gerrit_plugin_ext_test_deps(
    name = "providers_native_test_deps",
    ext_deps = [
        "com.nimbusds:nimbus-jose-jwt",
        "com.sap.cloud.security.java:api",
        "com.sap.cloud.security.java:security",
    ],
    plugin = PLUGIN,
)

gerrit_plugin_tests(
    name = "providers_native_tests",
    srcs = glob(PROVIDERS_NATIVE_TEST_SRCS),
    deps = [
        ":base",
        ":client",
        ":jwt",
        ":providers-native",
        ":providers_native_test_deps",
        ":utils",
    ],
)

gerrit_plugin_tests(
    name = "oauth_plugin_tests",
    srcs = glob(
        ["src/test/java/**/*.java"],
        exclude = PROVIDERS_NATIVE_TEST_SRCS,
    ),
    ext_deps = EXT_DEPS,
    plugin = PLUGIN,
    deps = [
        ":base",
        ":client",
        ":jwt",
        ":providers-native",
        ":utils",
    ],
)
