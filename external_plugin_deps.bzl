load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps(omit_commons_codec = True):
    JACKSON_VERS = "2.10.2"
    maven_jar(
        name = "scribejava-core",
        artifact = "com.github.scribejava:scribejava-core:6.9.0",
        sha1 = "ed761f450d8382f75787e8fee9ae52e7ec768747",
    )
    maven_jar(
        name = "jackson-annotations",
        artifact = "com.fasterxml.jackson.core:jackson-annotations:" + JACKSON_VERS,
        sha1 = "3a13b6105946541b8d4181a0506355b5fae63260",
    )
    maven_jar(
        name = "jackson-databind",
        artifact = "com.fasterxml.jackson.core:jackson-databind:" + JACKSON_VERS,
        sha1 = "0528de95f198afafbcfb0c09d2e43b6e0ea663ec",
        deps = [
            "@jackson-annotations//jar",
        ],
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
