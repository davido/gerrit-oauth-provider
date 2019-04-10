load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps(omit_commons_codec = True):
    maven_jar(
        name = "scribe",
        artifact = "org.scribe:scribe:1.3.7",
        sha1 = "583921bed46635d9f529ef5f14f7c9e83367bc6e",
    )
    maven_jar(
        name = "mockito",
        artifact = "org.mockito:mockito-core:2.27.0",
        sha1 = "835fc3283b481f4758b8ef464cd560c649c08b00",
        deps = [
            "@byte-buddy//jar",
            "@byte-buddy-agent//jar",
            "@objenesis//jar",
        ],
    )
    BYTE_BUDDY_VERSION = "1.9.10"
    maven_jar(
        name = "byte-buddy",
        artifact = "net.bytebuddy:byte-buddy:" + BYTE_BUDDY_VERSION,
        sha1 = "211a2b4d3df1eeef2a6cacf78d74a1f725e7a840",
    )
    maven_jar(
        name = "byte-buddy-agent",
        artifact = "net.bytebuddy:byte-buddy-agent:" + BYTE_BUDDY_VERSION,
        sha1 = "9674aba5ee793e54b864952b001166848da0f26b",
    )
    maven_jar(
        name = "objenesis",
        artifact = "org.objenesis:objenesis:2.6",
        sha1 = "639033469776fd37c08358c6b92a4761feb2af4b",
    )
    if not omit_commons_codec:
        maven_jar(
            name = "commons-codec",
            artifact = "commons-codec:commons-codec:1.4",
            sha1 = "4216af16d38465bbab0f3dff8efa14204f7a399a",
        )
