load(
    "@com_googlesource_gerrit_bazlets//:gerrit_plugin.bzl",
    "gerrit_plugin",
    "gerrit_plugin_ext_test_deps",
    "gerrit_plugin_library",
    "gerrit_plugin_tests",
)
load("@rules_java//java:defs.bzl", "java_binary")

EXT_DEPS = [
    "com.nimbusds:nimbus-jose-jwt",
    "com.sap.cloud.security.java:api",
    "com.sap.cloud.security.java:security",
]

PLUGIN = "oauth"

# The shared-core libraries every OAuth artifact bundles.
CORE_LIBS = [
    ":base",
    ":client",
    ":jwt",
    ":utils",
]

UTILS_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/utils/**/*.java"

gerrit_plugin_library(
    name = "utils",
    srcs = glob([UTILS_SRCS]),
)

CLIENT_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/client/**/*.java"

gerrit_plugin_library(
    name = "client",
    srcs = glob([CLIENT_SRCS]),
    deps = [":utils"],
)

BASE_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/base/**/*.java"

gerrit_plugin_library(
    name = "base",
    srcs = glob([BASE_SRCS]),
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
    deps = [":utils"],
)

DISCOVERY_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/discovery/**/*.java"

GITHUB_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/github/**/*.java"

GOOGLE_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/google/**/*.java"

KEYCLOAK_SRCS = "src/main/java/com/googlesource/gerrit/plugins/oauth/keycloak/**/*.java"

# Providers bundled only in the all-inclusive oauth plugin (no standalone artifact).
PROVIDERS_SRCS = [
    "src/main/java/com/googlesource/gerrit/plugins/oauth/airvantage/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/azure/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/bitbucket/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/cas/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/dex/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/facebook/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/gitlab/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/phabricator/**/*.java",
    "src/main/java/com/googlesource/gerrit/plugins/oauth/sapias/**/*.java",
]

gerrit_plugin_library(
    name = "discovery",
    srcs = glob(
        [DISCOVERY_SRCS],
        exclude = ["**/*PluginModule.java"],
    ),
    deps = CORE_LIBS,
)

gerrit_plugin_library(
    name = "github",
    srcs = glob(
        [GITHUB_SRCS],
        exclude = ["**/*PluginModule.java"],
    ),
    deps = CORE_LIBS,
)

gerrit_plugin_library(
    name = "google",
    srcs = glob(
        [GOOGLE_SRCS],
        exclude = ["**/*PluginModule.java"],
    ),
    deps = CORE_LIBS,
)

gerrit_plugin_library(
    name = "keycloak",
    srcs = glob(
        [KEYCLOAK_SRCS],
        exclude = ["**/*PluginModule.java"],
    ),
    deps = CORE_LIBS,
)

gerrit_plugin_library(
    name = "providers",
    srcs = glob(PROVIDERS_SRCS),
    ext_deps = [
        "com.sap.cloud.security.java:api",
        "com.sap.cloud.security.java:security",
        "com.sap.cloud.security:env",
        "com.sap.cloud.security.xsuaa:token-client",
    ],
    plugin = PLUGIN,
    deps = CORE_LIBS,
)

gerrit_plugin(
    srcs = glob(
        ["src/main/java/**/*.java"],
        exclude = [
            BASE_SRCS,
            CLIENT_SRCS,
            DISCOVERY_SRCS,
            GITHUB_SRCS,
            GOOGLE_SRCS,
            JWT_SRCS,
            KEYCLOAK_SRCS,
            UTILS_SRCS,
        ] + PROVIDERS_SRCS,
    ),
    ext_deps = [
        "com.sap.cloud.security:env",
        "com.sap.cloud.security.xsuaa:token-client",
    ] + EXT_DEPS,
    manifest_entries = [
        "Gerrit-PluginName: gerrit-oauth-provider",
        "Gerrit-Module: com.googlesource.gerrit.plugins.oauth.Module",
        "Gerrit-InitStep: com.googlesource.gerrit.plugins.oauth.InitOAuth",
        "Implementation-Title: Gerrit OAuth authentication provider",
        "Implementation-URL: https://github.com/davido/gerrit-oauth-provider",
    ],
    plugin = PLUGIN,
    resources = glob(["src/main/resources/**/*"]),
    deps = CORE_LIBS + [
        ":discovery",
        ":github",
        ":google",
        ":keycloak",
        ":providers",
    ],
)

[
    gerrit_plugin(
        name = "oauth-" + provider,
        srcs = ["src/main/java/com/googlesource/gerrit/plugins/oauth/%s/%s.java" % (provider, module)],
        dir_name = PLUGIN,
        manifest_entries = [
            "Gerrit-PluginName: gerrit-oauth-provider",
            "Gerrit-Module: com.googlesource.gerrit.plugins.oauth.%s.%s" % (provider, module),
            "Gerrit-InitStep: com.googlesource.gerrit.plugins.oauth.%s.%s" % (provider, init),
            "Implementation-Title: Gerrit OAuth authentication provider for %s" % provider,
            "Implementation-URL: https://github.com/davido/gerrit-oauth-provider",
        ],
        resources = glob(["src/main/resources/**/*"]),
        deps = CORE_LIBS + [":" + provider],
    )
    for provider, module, init in [
        ("discovery", "DiscoveryPluginModule", "DiscoveryInitStep"),
        ("github", "GitHubPluginModule", "GitHubInitStep"),
        ("google", "GooglePluginModule", "GoogleInitStep"),
        ("keycloak", "KeycloakPluginModule", "KeycloakInitStep"),
    ]
]

PROVIDERS_TEST_SRCS = [
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
    "src/test/java/com/googlesource/gerrit/plugins/oauth/sapias/**/*.java",
]

gerrit_plugin_ext_test_deps(
    name = "providers_test_deps",
    ext_deps = [
        "com.nimbusds:nimbus-jose-jwt",
        "com.sap.cloud.security.java:api",
        "com.sap.cloud.security.java:security",
    ],
    plugin = PLUGIN,
)

gerrit_plugin_tests(
    name = "providers_tests",
    srcs = glob(PROVIDERS_TEST_SRCS),
    deps = CORE_LIBS + [
        ":discovery",
        ":github",
        ":google",
        ":keycloak",
        ":providers",
        ":providers_test_deps",
    ],
)

gerrit_plugin_tests(
    name = "oauth_plugin_tests",
    srcs = glob(
        ["src/test/java/**/*.java"],
        exclude = PROVIDERS_TEST_SRCS,
    ),
    ext_deps = EXT_DEPS,
    plugin = PLUGIN,
    deps = CORE_LIBS + [":providers"],
)
