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
  static final String LINK_TO_EXISTING_OPENID_ACCOUNT =
      "link-to-existing-openid-accounts";
  static final String DOMAIN = "domain";
  static final String USE_EMAIL_AS_USERNAME =
      "use-email-as-username";

  private final ConsoleUI ui;
  private final Section googleOAuthProviderSection;
  private final Section githubOAuthProviderSection;
  private final Section bitbucketOAuthProviderSection;

  @Inject
  InitOAuth(ConsoleUI ui,
      Section.Factory sections,
      @PluginName String pluginName) {
    this.ui = ui;
    this.googleOAuthProviderSection = sections.get(
        PLUGIN_SECTION, pluginName + GoogleOAuthService.CONFIG_SUFFIX);
    this.githubOAuthProviderSection = sections.get(
        PLUGIN_SECTION, pluginName + GitHubOAuthService.CONFIG_SUFFIX);
    this.bitbucketOAuthProviderSection = sections.get(
        PLUGIN_SECTION, pluginName + BitbucketOAuthService.CONFIG_SUFFIX);
  }

  @Override
  public void run() throws Exception {
    ui.header("OAuth Authentication Provider");

    boolean configureGoogleOAuthProvider = ui.yesno(
        true, "Use Google OAuth provider for Gerrit login ?");
    if (configureGoogleOAuthProvider) {
      configureOAuth(googleOAuthProviderSection);
      googleOAuthProviderSection.string(
          "Link to OpenID accounts?",
          LINK_TO_EXISTING_OPENID_ACCOUNT, "true");
    }

    boolean configueGitHubOAuthProvider = ui.yesno(
        true, "Use GitHub OAuth provider for Gerrit login ?");
    if (configueGitHubOAuthProvider) {
      configureOAuth(githubOAuthProviderSection);
    }

    boolean configureBitbucketOAuthProvider = ui.yesno(
        true, "Use Bitbucket OAuth provider for Gerrit login ?");
    if (configureBitbucketOAuthProvider) {
      configureOAuth(bitbucketOAuthProviderSection);
    }
  }

  private void configureOAuth(Section s) {
    s.string("Application client id", CLIENT_ID, null);
    s.passwordForKey("Application client secret", CLIENT_SECRET);
  }

  @Override
  public void postRun() throws Exception {
  }
}
