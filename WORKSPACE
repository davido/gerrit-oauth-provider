workspace(name = "com_github_davido_gerrit_oauth_provider")

git_repository(
    name = "com_github_davido_bazlets",
    #commit = "v0.1",
    commit = "85d0949941406d1003f801c3981977a1d7aad518",
    remote = "https://github.com/davido/bazlets.git",
)

#local_repository(
#  name = "com_github_davido_bazlets",
#  path = "/home/davido/projects/bazlets",
#)

# Release Plugin API
load(
    "@com_github_davido_bazlets//:gerrit_api.bzl",
    "gerrit_api",
)

gerrit_api()

load("@com_github_davido_bazlets//tools:maven_jar.bzl", "maven_jar")

maven_jar(
    name = "scribe_artifact",
    artifact = "org.scribe:scribe:1.3.7",
    sha1 = "583921bed46635d9f529ef5f14f7c9e83367bc6e",
)

bind(
    name = "scribe",
    actual = "@scribe_artifact//jar",
)

maven_jar(
    name = "commons_codec",
    artifact = "commons-codec:commons-codec:1.4",
    sha1 = "4216af16d38465bbab0f3dff8efa14204f7a399a",
)

bind(
    name = "commons-codec-neverlink",
    actual = "@commons_codec//jar:neverlink",
)
