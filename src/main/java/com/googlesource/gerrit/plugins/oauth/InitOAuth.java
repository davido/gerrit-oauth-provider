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

import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ENABLE_GIT_OVER_HTTP;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ENABLE_PKCE;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.FIX_LEGACY_USER_ID;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ROOT_URL;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.TENANT;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.Section;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.oauth.airvantage.AirVantageOAuthService;
import com.googlesource.gerrit.plugins.oauth.azure.AzureActiveDirectoryService;
import com.googlesource.gerrit.plugins.oauth.base.AbstractOAuthInitStep;
import com.googlesource.gerrit.plugins.oauth.bitbucket.BitbucketOAuthService;
import com.googlesource.gerrit.plugins.oauth.cas.CasOAuthService;
import com.googlesource.gerrit.plugins.oauth.dex.DexOAuthService;
import com.googlesource.gerrit.plugins.oauth.discovery.DiscoveryInitStep;
import com.googlesource.gerrit.plugins.oauth.facebook.FacebookOAuthService;
import com.googlesource.gerrit.plugins.oauth.github.GitHubInitStep;
import com.googlesource.gerrit.plugins.oauth.gitlab.GitLabOAuthService;
import com.googlesource.gerrit.plugins.oauth.google.GoogleInitStep;
import com.googlesource.gerrit.plugins.oauth.keycloak.KeycloakInitStep;
import com.googlesource.gerrit.plugins.oauth.phabricator.PhabricatorOAuthService;

/**
 * All-inclusive {@code oauth} plugin init step. Delegates the providers that also ship as
 * standalone artifacts to their own init steps, and prompts for the remaining providers inline.
 */
public class InitOAuth extends AbstractOAuthInitStep {

  @Inject
  InitOAuth(ConsoleUI ui, Section.Factory sections, @PluginName String pluginName) {
    super(ui, sections, pluginName);
  }

  @Override
  public void configure() throws Exception {
    new GoogleInitStep(ui, sections, pluginName).configure();
    new GitHubInitStep(ui, sections, pluginName).configure();

    Section bitbucket = getConfigSection(BitbucketOAuthService.class);
    if (ui.yesno(isConfigured(bitbucket), "Use Bitbucket OAuth provider for Gerrit login?")
        && configureOAuth(bitbucket)) {
      bitbucket.string(FIX_LEGACY_USER_ID_QUESTION, FIX_LEGACY_USER_ID, "false");
      bitbucket.string("Enable PKCE for Bitbucket OAuth provider?", ENABLE_PKCE, "false");
    }

    Section cas = getConfigSection(CasOAuthService.class);
    if (ui.yesno(isConfigured(cas), "Use CAS OAuth provider for Gerrit login?")
        && configureOAuth(cas)) {
      checkRootUrl(cas.string("CAS Root URL", ROOT_URL, null));
      cas.string(FIX_LEGACY_USER_ID_QUESTION, FIX_LEGACY_USER_ID, "false");
      cas.string("Enable PKCE for CAS OAuth provider?", ENABLE_PKCE, "false");
    }

    Section facebook = getConfigSection(FacebookOAuthService.class);
    if (ui.yesno(isConfigured(facebook), "Use Facebook OAuth provider for Gerrit login?")
        && configureOAuth(facebook)) {
      facebook.string("Enable PKCE for Facebook OAuth provider?", ENABLE_PKCE, "false");
    }

    Section gitlab = getConfigSection(GitLabOAuthService.class);
    if (ui.yesno(isConfigured(gitlab), "Use GitLab OAuth provider for Gerrit login?")
        && configureOAuth(gitlab)) {
      checkRootUrl(gitlab.string("GitLab Root URL", ROOT_URL, null));
      gitlab.string("Enable PKCE for GitLab OAuth provider?", ENABLE_PKCE, "false");
      gitlab.string(
          "Enable Git-over-HTTP for GitLab OAuth provider?", ENABLE_GIT_OVER_HTTP, "false");
    }

    Section dex = getConfigSection(DexOAuthService.class);
    if (ui.yesno(isConfigured(dex), "Use Dex OAuth provider for Gerrit login?")
        && configureOAuth(dex)) {
      checkRootUrl(dex.string("Dex Root URL", ROOT_URL, null));
      dex.string("Enable PKCE for Dex OAuth provider?", ENABLE_PKCE, "false");
    }

    new KeycloakInitStep(ui, sections, pluginName).configure();

    Section azure = getConfigSection(AzureActiveDirectoryService.class);
    if (ui.yesno(isConfigured(azure), "Use Azure OAuth provider for Gerrit login?")) {
      configureOAuth(azure);
      azure.string("Tenant", TENANT, AzureActiveDirectoryService.DEFAULT_TENANT);
      azure.string("Enable PKCE for Azure OAuth provider?", ENABLE_PKCE, "false");
      azure.string("Enable Git-over-HTTP for Azure OAuth provider?", ENABLE_GIT_OVER_HTTP, "false");
    }

    Section airVantage = getConfigSection(AirVantageOAuthService.class);
    if (ui.yesno(isConfigured(airVantage), "Use AirVantage OAuth provider for Gerrit login?")
        && configureOAuth(airVantage)) {
      airVantage.string("Enable PKCE for AirVantage OAuth provider?", ENABLE_PKCE, "false");
    }

    Section phabricator = getConfigSection(PhabricatorOAuthService.class);
    if (ui.yesno(isConfigured(phabricator), "Use Phabricator OAuth provider for Gerrit login?")
        && configureOAuth(phabricator)) {
      checkRootUrl(phabricator.string("Phabricator Root URL", ROOT_URL, null));
      phabricator.string("Enable PKCE for Phabricator OAuth provider?", ENABLE_PKCE, "false");
    }

    new DiscoveryInitStep(ui, sections, pluginName).configure();
  }
}
