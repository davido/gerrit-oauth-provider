// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.googlesource.gerrit.plugins.oauth;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.InitStep;
import com.google.gerrit.pgm.init.api.Section;
import com.google.inject.Inject;

class InitOAuth implements InitStep {
  static final String PLUGIN_SECTION = "plugin";
  static final String CLIENT_ID = "client-id";
  static final String CLIENT_SECRET = "client-secret";
  static final String LINK_TO_EXISTING_OPENID_ACCOUNT = "link-to-existing-openid-accounts";
  static final String FIX_LEGACY_USER_ID = "fix-legacy-user-id";
  static final String DOMAIN = "domain";
  static final String USE_EMAIL_AS_USERNAME = "use-email-as-username";
  static final String ROOT_URL = "root-url";
  static final String REALM = "realm";
  static final String SERVICE_NAME = "service-name";
  static String FIX_LEGACY_USER_ID_QUESTION = "Fix legacy user id, without oauth provider prefix?";

  private final ConsoleUI ui;
  private final Section googleOAuthProviderSection;
  private final Section githubOAuthProviderSection;
  private final Section bitbucketOAuthProviderSection;
  private final Section casOAuthProviderSection;
  private final Section facebookOAuthProviderSection;
  private final Section gitlabOAuthProviderSection;
  private final Section dexOAuthProviderSection;
  private final Section keycloakOAuthProviderSection;

  @Inject
  InitOAuth(ConsoleUI ui, Section.Factory sections, @PluginName String pluginName) {
    this.ui = ui;
    this.googleOAuthProviderSection =
        sections.get(PLUGIN_SECTION, pluginName + GoogleOAuthService.CONFIG_SUFFIX);
    this.githubOAuthProviderSection =
        sections.get(PLUGIN_SECTION, pluginName + GitHubOAuthService.CONFIG_SUFFIX);
    this.bitbucketOAuthProviderSection =
        sections.get(PLUGIN_SECTION, pluginName + BitbucketOAuthService.CONFIG_SUFFIX);
    this.casOAuthProviderSection =
        sections.get(PLUGIN_SECTION, pluginName + CasOAuthService.CONFIG_SUFFIX);
    this.facebookOAuthProviderSection =
        sections.get(PLUGIN_SECTION, pluginName + FacebookOAuthService.CONFIG_SUFFIX);
    this.gitlabOAuthProviderSection =
        sections.get(PLUGIN_SECTION, pluginName + GitLabOAuthService.CONFIG_SUFFIX);
    this.dexOAuthProviderSection =
        sections.get(PLUGIN_SECTION, pluginName + DexOAuthService.CONFIG_SUFFIX);
    this.keycloakOAuthProviderSection =
            sections.get(PLUGIN_SECTION, pluginName + KeycloakOAuthService.CONFIG_SUFFIX);
  }

  @Override
  public void run() throws Exception {
    ui.header("OAuth Authentication Provider");

    boolean configureGoogleOAuthProvider =
        ui.yesno(true, "Use Google OAuth provider for Gerrit login ?");
    if (configureGoogleOAuthProvider) {
      configureOAuth(googleOAuthProviderSection);
      googleOAuthProviderSection.string(FIX_LEGACY_USER_ID_QUESTION, FIX_LEGACY_USER_ID, "false");
    }

    boolean configueGitHubOAuthProvider =
        ui.yesno(true, "Use GitHub OAuth provider for Gerrit login ?");
    if (configueGitHubOAuthProvider) {
      configureOAuth(githubOAuthProviderSection);
      githubOAuthProviderSection.string(FIX_LEGACY_USER_ID_QUESTION, FIX_LEGACY_USER_ID, "false");
    }

    boolean configureBitbucketOAuthProvider =
        ui.yesno(true, "Use Bitbucket OAuth provider for Gerrit login ?");
    if (configureBitbucketOAuthProvider) {
      configureOAuth(bitbucketOAuthProviderSection);
      bitbucketOAuthProviderSection.string(
          FIX_LEGACY_USER_ID_QUESTION, FIX_LEGACY_USER_ID, "false");
    }

    boolean configureCasOAuthProvider = ui.yesno(true, "Use CAS OAuth provider for Gerrit login ?");
    if (configureCasOAuthProvider) {
      casOAuthProviderSection.string("CAS Root URL", ROOT_URL, null);
      configureOAuth(casOAuthProviderSection);
      casOAuthProviderSection.string(FIX_LEGACY_USER_ID_QUESTION, FIX_LEGACY_USER_ID, "false");
    }

    boolean configueFacebookOAuthProvider =
        ui.yesno(true, "Use Facebook OAuth provider for Gerrit login ?");
    if (configueFacebookOAuthProvider) {
      configureOAuth(facebookOAuthProviderSection);
    }

    boolean configureGitLabOAuthProvider =
        ui.yesno(true, "Use GitLab OAuth provider for Gerrit login ?");
    if (configureGitLabOAuthProvider) {
      gitlabOAuthProviderSection.string("GitLab Root URL", ROOT_URL, null);
      configureOAuth(gitlabOAuthProviderSection);
    }

    boolean configureDexOAuthProvider = ui.yesno(true, "Use Dex OAuth provider for Gerrit login ?");
    if (configureDexOAuthProvider) {
      dexOAuthProviderSection.string("Dex Root URL", ROOT_URL, null);
      configureOAuth(dexOAuthProviderSection);
    }

    boolean configureKeycloakOAuthProvider = ui.yesno(true, "Use Keycloak OAuth provider for Gerrit login ?");
    if (configureKeycloakOAuthProvider) {
      keycloakOAuthProviderSection.string("Keycloak Root URL", ROOT_URL, null);
      keycloakOAuthProviderSection.string("Keycloak Realm", REALM, null);
      configureOAuth(keycloakOAuthProviderSection);
    }
  }

  private void configureOAuth(Section s) {
    s.string("Application client id", CLIENT_ID, null);
    s.passwordForKey("Application client secret", CLIENT_SECRET);
  }

  @Override
  public void postRun() throws Exception {}
}
