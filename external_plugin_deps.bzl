load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps(omit_commons_codec = True):
  maven_jar(
      name = "scribe",
      artifact = "org.scribe:scribe:1.3.7",
      sha1 = "583921bed46635d9f529ef5f14f7c9e83367bc6e",
  )
  if not omit_commons_codec:
    maven_jar(
       name = "commons-codec",
       artifact = "commons-codec:commons-codec:1.4",
       sha1 = "4216af16d38465bbab0f3dff8efa14204f7a399a",
    )

