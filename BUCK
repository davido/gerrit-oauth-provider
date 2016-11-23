include_defs('//bucklets/gerrit_plugin.bucklet')
include_defs('//bucklets/maven_jar.bucklet')
define_license('scribe')

gerrit_plugin(
  name = 'oauth',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/resources/**/*']),
  manifest_entries = [
    'Gerrit-PluginName: oauth',
    'Gerrit-HttpModule: com.googlesource.gerrit.plugins.oauth.HttpModule',
    'Gerrit-InitStep: com.googlesource.gerrit.plugins.oauth.InitOAuth',
    'Implementation-Title: Gerrit OAuth authentication provider',
    'Implementation-URL: https://github.com/davido/gerrit-oauth-provider',
  ],
  deps = [
    ':scribe'
  ],
  provided_deps = [
    '//lib:guava',
    '//lib:gson',
    '//lib/commons:codec',
  ],
)

java_library(
  name = 'classpath',
  deps = [':oauth__plugin'],
)

maven_jar(
  name = 'scribe',
  id = 'org.scribe:scribe:1.3.7',
  sha1 = '583921bed46635d9f529ef5f14f7c9e83367bc6e',
  license = 'scribe',
  local_license = True,
)
