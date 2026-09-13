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

import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.CLIENT_AUTH_METHOD;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.CLIENT_ID;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.CLIENT_SECRET;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ENABLE_GIT_OVER_HTTP;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ENABLE_PKCE;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.FIX_LEGACY_USER_ID;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.LINK_TO_EXISTING_GERRIT_ACCOUNT;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.REALM;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ROOT_URL;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.TENANT;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.InitStep;
import com.google.gerrit.pgm.init.api.Section;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.googlesource.gerrit.plugins.oauth.airvantage.AirVantageOAuthService;
import com.googlesource.gerrit.plugins.oauth.azure.AzureActiveDirectoryService;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderConfig;
import com.googlesource.gerrit.plugins.oauth.bitbucket.BitbucketOAuthService;
import com.googlesource.gerrit.plugins.oauth.cas.CasOAuthService;
import com.googlesource.gerrit.plugins.oauth.dex.DexOAuthService;
import com.googlesource.gerrit.plugins.oauth.discovery.DiscoveryOAuthService;
import com.googlesource.gerrit.plugins.oauth.facebook.FacebookOAuthService;
import com.googlesource.gerrit.plugins.oauth.github.GitHubOAuthService;
import com.googlesource.gerrit.plugins.oauth.gitlab.GitLabOAuthService;
import com.googlesource.gerrit.plugins.oauth.google.GoogleOAuthService;
import com.googlesource.gerrit.plugins.oauth.keycloak.KeycloakOAuthService;
import com.googlesource.gerrit.plugins.oauth.phabricator.PhabricatorOAuthService;
import com.googlesource.gerrit.plugins.oauth.sap.SAPIasOAuthService;
import java.net.URI;

public class InitOAuth implements InitStep {
  static final String PLUGIN_SECTION = "plugin";
  static String FIX_LEGACY_USER_ID_QUESTION = "Fix legacy user id, without oauth provider prefix?";

  private final ConsoleUI ui;
  private final Section.Factory sections;
  private final String pluginName;
  private final Section iasOAuthProviderSection;
  private final Section googleOAuthProviderSection;
  private final Section githubOAuthProviderSection;
  private final Section bitbucketOAuthProviderSection;
  private final Section casOAuthProviderSection;
  private final Section facebookOAuthProviderSection;
  private final Section gitlabOAuthProviderSection;
  private final Section dexOAuthProviderSection;
  private final Section keycloakOAuthProviderSection;
  private final Section azureActiveDirectoryAuthProviderSection;
  private final Section airVantageOAuthProviderSection;
  private final Section phabricatorOAuthProviderSection;
  private final Section discoveryOAuthProviderSection;

  @Inject
  InitOAuth(ConsoleUI ui, Section.Factory sections, @PluginName String pluginName) {
    this.ui = ui;
    this.sections = sections;
    this.pluginName = pluginName;
    this.googleOAuthProviderSection = getConfigSection(GoogleOAuthService.class);
    this.githubOAuthProviderSection = getConfigSection(GitHubOAuthService.class);
    this.bitbucketOAuthProviderSection = getConfigSection(BitbucketOAuthService.class);
    this.casOAuthProviderSection = getConfigSection(CasOAuthService.class);
    this.facebookOAuthProviderSection = getConfigSection(FacebookOAuthService.class);
    this.gitlabOAuthProviderSection = getConfigSection(GitLabOAuthService.class);
    this.dexOAuthProviderSection = getConfigSection(DexOAuthService.class);
    this.keycloakOAuthProviderSection = getConfigSection(KeycloakOAuthService.class);
    this.azureActiveDirectoryAuthProviderSection =
        getConfigSection(AzureActiveDirectoryService.class);
    this.airVantageOAuthProviderSection = getConfigSection(AirVantageOAuthService.class);
    this.phabricatorOAuthProviderSection = getConfigSection(PhabricatorOAuthService.class);
    this.iasOAuthProviderSection = getConfigSection(SAPIasOAuthService.class);
    this.discoveryOAuthProviderSection = getConfigSection(DiscoveryOAuthService.class);
  }

  @Override
  public void run() throws Exception {
    ui.header("OAuth Authentication Provider");

    boolean configureGoogleOAuthProvider =
        ui.yesno(
            isConfigured(googleOAuthProviderSection),
            "Use Google OAuth provider for Gerrit login?");
    if (configureGoogleOAuthProvider && configureOAuth(googleOAuthProviderSection)) {
      googleOAuthProviderSection.string(FIX_LEGACY_USER_ID_QUESTION, FIX_LEGACY_USER_ID, "false");
      googleOAuthProviderSection.string(
          "Enable PKCE for Google OAuth provider?", ENABLE_PKCE, "false");
      googleOAuthProviderSection.string(
          "Enable Git-over-HTTP for Google OAuth provider?", ENABLE_GIT_OVER_HTTP, "false");
    }

    boolean configueGitHubOAuthProvider =
        ui.yesno(
            isConfigured(githubOAuthProviderSection),
            "Use GitHub OAuth provider for Gerrit login?");
    if (configueGitHubOAuthProvider && configureOAuth(githubOAuthProviderSection)) {
      githubOAuthProviderSection.string(FIX_LEGACY_USER_ID_QUESTION, FIX_LEGACY_USER_ID, "false");
      githubOAuthProviderSection.string(
          "Enable PKCE for GitHub OAuth provider?", ENABLE_PKCE, "false");
      githubOAuthProviderSection.string(
          "Enable Git-over-HTTP for GitHub OAuth provider?", ENABLE_GIT_OVER_HTTP, "false");
    }

    boolean configureBitbucketOAuthProvider =
        ui.yesno(
            isConfigured(bitbucketOAuthProviderSection),
            "Use Bitbucket OAuth provider for Gerrit login?");
    if (configureBitbucketOAuthProvider && configureOAuth(bitbucketOAuthProviderSection)) {
      bitbucketOAuthProviderSection.string(
          FIX_LEGACY_USER_ID_QUESTION, FIX_LEGACY_USER_ID, "false");
      bitbucketOAuthProviderSection.string(
          "Enable PKCE for Bitbucket OAuth provider?", ENABLE_PKCE, "false");
    }

    boolean configureCasOAuthProvider =
        ui.yesno(isConfigured(casOAuthProviderSection), "Use CAS OAuth provider for Gerrit login?");
    if (configureCasOAuthProvider && configureOAuth(casOAuthProviderSection)) {
      checkRootUrl(casOAuthProviderSection.string("CAS Root URL", ROOT_URL, null));
      casOAuthProviderSection.string(FIX_LEGACY_USER_ID_QUESTION, FIX_LEGACY_USER_ID, "false");
      casOAuthProviderSection.string("Enable PKCE for CAS OAuth provider?", ENABLE_PKCE, "false");
    }

    boolean configueFacebookOAuthProvider =
        ui.yesno(
            isConfigured(facebookOAuthProviderSection),
            "Use Facebook OAuth provider for Gerrit login?");
    if (configueFacebookOAuthProvider && configureOAuth(facebookOAuthProviderSection)) {
      facebookOAuthProviderSection.string(
          "Enable PKCE for Facebook OAuth provider?", ENABLE_PKCE, "false");
    }

    boolean configureGitLabOAuthProvider =
        ui.yesno(
            isConfigured(gitlabOAuthProviderSection),
            "Use GitLab OAuth provider for Gerrit login?");
    if (configureGitLabOAuthProvider && configureOAuth(gitlabOAuthProviderSection)) {
      checkRootUrl(gitlabOAuthProviderSection.string("GitLab Root URL", ROOT_URL, null));
      gitlabOAuthProviderSection.string(
          "Enable PKCE for GitLab OAuth provider?", ENABLE_PKCE, "false");
      gitlabOAuthProviderSection.string(
          "Enable Git-over-HTTP for GitLab OAuth provider?", ENABLE_GIT_OVER_HTTP, "false");
    }

    boolean configureIASOAuthProvider =
        ui.yesno(
            isConfigured(iasOAuthProviderSection), "Use SAP IAS OAuth provider for Gerrit login?");
    if (configureIASOAuthProvider && configureOAuth(iasOAuthProviderSection)) {
      checkRootUrl(iasOAuthProviderSection.string("SAP IAS Root URL", ROOT_URL, null));
      iasOAuthProviderSection.string(
          "Enable PKCE for SAP IAS OAuth provider?", ENABLE_PKCE, "false");
      iasOAuthProviderSection.string(
          "Enable Git-over-HTTP for SAP IAS OAuth provider?", ENABLE_GIT_OVER_HTTP, "true");
    }

    boolean configureDexOAuthProvider =
        ui.yesno(isConfigured(dexOAuthProviderSection), "Use Dex OAuth provider for Gerrit login?");
    if (configureDexOAuthProvider && configureOAuth(dexOAuthProviderSection)) {
      checkRootUrl(dexOAuthProviderSection.string("Dex Root URL", ROOT_URL, null));
      dexOAuthProviderSection.string("Enable PKCE for Dex OAuth provider?", ENABLE_PKCE, "false");
    }

    boolean configureKeycloakOAuthProvider =
        ui.yesno(
            isConfigured(keycloakOAuthProviderSection),
            "Use Keycloak OAuth provider for Gerrit login?");
    if (configureKeycloakOAuthProvider && configureOAuth(keycloakOAuthProviderSection)) {
      checkRootUrl(keycloakOAuthProviderSection.string("Keycloak Root URL", ROOT_URL, null));
      keycloakOAuthProviderSection.string("Keycloak Realm", REALM, null);
      keycloakOAuthProviderSection.string(
          "Enable PKCE for Keycloak OAuth provider?", ENABLE_PKCE, "false");
      keycloakOAuthProviderSection.string(
          "Enable Git-over-HTTP for Keycloak OAuth provider?", ENABLE_GIT_OVER_HTTP, "false");
    }

    boolean configureAzureActiveDirectoryAuthProvider =
        ui.yesno(
            isConfigured(azureActiveDirectoryAuthProviderSection),
            "Use Azure OAuth provider for Gerrit login?");
    if (configureAzureActiveDirectoryAuthProvider) {
      configureOAuth(azureActiveDirectoryAuthProviderSection);
      azureActiveDirectoryAuthProviderSection.string(
          "Tenant", TENANT, AzureActiveDirectoryService.DEFAULT_TENANT);
      azureActiveDirectoryAuthProviderSection.string(
          "Enable PKCE for Azure OAuth provider?", ENABLE_PKCE, "false");
      azureActiveDirectoryAuthProviderSection.string(
          "Enable Git-over-HTTP for Azure OAuth provider?", ENABLE_GIT_OVER_HTTP, "false");
    }

    boolean configureAirVantageOAuthProvider =
        ui.yesno(
            isConfigured(airVantageOAuthProviderSection),
            "Use AirVantage OAuth provider for Gerrit login?");
    if (configureAirVantageOAuthProvider && configureOAuth(airVantageOAuthProviderSection)) {
      airVantageOAuthProviderSection.string(
          "Enable PKCE for AirVantage OAuth provider?", ENABLE_PKCE, "false");
    }

    boolean configurePhabricatorOAuthProvider =
        ui.yesno(
            isConfigured(phabricatorOAuthProviderSection),
            "Use Phabricator OAuth provider for Gerrit login?");
    if (configurePhabricatorOAuthProvider && configureOAuth(phabricatorOAuthProviderSection)) {
      checkRootUrl(phabricatorOAuthProviderSection.string("Phabricator Root URL", ROOT_URL, null));
      phabricatorOAuthProviderSection.string(
          "Enable PKCE for Phabricator OAuth provider?", ENABLE_PKCE, "false");
    }

    boolean configureDiscoveryOAuthProvider =
        ui.yesno(
            isConfigured(discoveryOAuthProviderSection),
            "Use Well Known Discovery OAuth provider for Gerrit login?");
    if (configureDiscoveryOAuthProvider && configureOAuth(discoveryOAuthProviderSection)) {
      checkRootUrl(
          discoveryOAuthProviderSection.string(
              "Discovery Root URL(before `/.well-known')", ROOT_URL, null));
      discoveryOAuthProviderSection.string(
          "Enable PKCE for Discovery OAuth provider?", ENABLE_PKCE, "false");
      discoveryOAuthProviderSection.string(
          "Enable Git-over-HTTP for Discovery OAuth provider?", ENABLE_GIT_OVER_HTTP, "false");
      discoveryOAuthProviderSection.string(
          "Link to existing gerrit accounts?", LINK_TO_EXISTING_GERRIT_ACCOUNT, "false");
      discoveryOAuthProviderSection.string(
          "Client authentication method (basic or request-body)?", CLIENT_AUTH_METHOD, "basic");
    }
  }

  /**
   * Retrieve client id to check whether or not this provider was already configured.
   *
   * @param s OAuth provider section
   * @return true if client id key is present, false otherwise
   */
  private static boolean isConfigured(Section s) {
    return !Strings.isNullOrEmpty(s.get(CLIENT_ID));
  }

  /**
   * Configure OAuth provider section
   *
   * @param s section to configure
   * @return true if section is present, false otherwise
   */
  private static boolean configureOAuth(Section s) {
    if (!Strings.isNullOrEmpty(s.string("Application client id", CLIENT_ID, null))) {
      s.passwordForKey("Application client secret", CLIENT_SECRET);
      return true;
    }
    return false;
  }

  /**
   * Check root URL parameter. It must be not null and it must be an absolute URI.
   *
   * @param rootUrl root URL
   * @throws ProvisionException if rootUrl wasn't provided or is not absolute URI.
   */
  private static void checkRootUrl(String rootUrl) {
    requireNonNull(rootUrl);
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
  }

  private Section getConfigSection(Class<? extends OAuthServiceProvider> serviceClass) {
    String serviceProviderName =
        serviceClass.getAnnotation(OAuthServiceProviderConfig.class).name();
    return getConfigSection(serviceProviderName);
  }

  private Section getConfigSection(String serviceProviderName) {
    String sectionName = pluginName + "-" + serviceProviderName + "-oauth";
    return sections.get(PLUGIN_SECTION, sectionName);
  }

  @Override
  public void postRun() throws Exception {}
}
