load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps():
  maven_jar(
      name = "scribe",
      artifact = "org.scribe:scribe:1.3.7",
      sha1 = "583921bed46635d9f529ef5f14f7c9e83367bc6e",
   )
