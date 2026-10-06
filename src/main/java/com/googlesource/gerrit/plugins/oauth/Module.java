// Copyright (C) 2017 The Android Open Source Project
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
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.server.account.externalids.ExternalIdFactory;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.oauth.airvantage.AirVantageOAuthService;
import com.googlesource.gerrit.plugins.oauth.azure.AzureActiveDirectoryService;
import com.googlesource.gerrit.plugins.oauth.azure.AzureModule;
import com.googlesource.gerrit.plugins.oauth.azure.AzureOAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.base.AbstractOAuthModule;
import com.googlesource.gerrit.plugins.oauth.base.SupportedLoginProvider;
import com.googlesource.gerrit.plugins.oauth.bitbucket.BitbucketOAuthService;
import com.googlesource.gerrit.plugins.oauth.cas.CasOAuthService;
import com.googlesource.gerrit.plugins.oauth.dex.DexOAuthService;
import com.googlesource.gerrit.plugins.oauth.discovery.DiscoveryModule;
import com.googlesource.gerrit.plugins.oauth.discovery.DiscoveryOAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.discovery.DiscoveryOAuthService;
import com.googlesource.gerrit.plugins.oauth.facebook.FacebookOAuthService;
import com.googlesource.gerrit.plugins.oauth.github.GitHubModule;
import com.googlesource.gerrit.plugins.oauth.github.GitHubOAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.github.GitHubOAuthService;
import com.googlesource.gerrit.plugins.oauth.gitlab.GitLabModule;
import com.googlesource.gerrit.plugins.oauth.gitlab.GitLabOAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.gitlab.GitLabOAuthService;
import com.googlesource.gerrit.plugins.oauth.google.GoogleModule;
import com.googlesource.gerrit.plugins.oauth.google.GoogleOAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.google.GoogleOAuthService;
import com.googlesource.gerrit.plugins.oauth.keycloak.KeycloakModule;
import com.googlesource.gerrit.plugins.oauth.keycloak.KeycloakOAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.keycloak.KeycloakOAuthService;
import com.googlesource.gerrit.plugins.oauth.phabricator.PhabricatorOAuthService;
import com.googlesource.gerrit.plugins.oauth.sapias.SAPIasModule;
import com.googlesource.gerrit.plugins.oauth.sapias.SAPIasOAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.sapias.SAPIasOAuthService;
import java.util.List;
import org.eclipse.jgit.lib.Config;

/** All-inclusive {@code oauth} plugin module: bundles every OAuth provider. */
public class Module extends AbstractOAuthModule {

  private static final List<SupportedLoginProvider> SUPPORTED_LOGIN_PROVIDERS =
      List.of(
          // SAP is grandfathered on; newer providers default off (opt-in via enable-git-over-http).
          new SupportedLoginProvider(SAPIasOAuthLoginProvider.class, SAPIasModule::new, true),
          new SupportedLoginProvider(KeycloakOAuthLoginProvider.class, KeycloakModule::new, false),
          new SupportedLoginProvider(
              DiscoveryOAuthLoginProvider.class, DiscoveryModule::new, false),
          new SupportedLoginProvider(GoogleOAuthLoginProvider.class, GoogleModule::new, false),
          new SupportedLoginProvider(GitHubOAuthLoginProvider.class, GitHubModule::new, false),
          new SupportedLoginProvider(GitLabOAuthLoginProvider.class, GitLabModule::new, false),
          new SupportedLoginProvider(AzureOAuthLoginProvider.class, AzureModule::new, false));

  private static final List<Class<? extends OAuthServiceProvider>> ALL_SERVICE_PROVIDERS =
      List.of(
          AirVantageOAuthService.class,
          AzureActiveDirectoryService.class,
          BitbucketOAuthService.class,
          CasOAuthService.class,
          DexOAuthService.class,
          DiscoveryOAuthService.class,
          FacebookOAuthService.class,
          GitHubOAuthService.class,
          GitLabOAuthService.class,
          GoogleOAuthService.class,
          KeycloakOAuthService.class,
          PhabricatorOAuthService.class,
          SAPIasOAuthService.class);

  @Inject
  public Module(
      @GerritServerConfig Config config,
      @PluginName String pluginName,
      ExternalIdFactory externalIdFactory) {
    super(config, pluginName, externalIdFactory);
  }

  @Override
  protected List<Class<? extends OAuthServiceProvider>> serviceProviders() {
    return ALL_SERVICE_PROVIDERS;
  }

  @Override
  protected List<SupportedLoginProvider> loginProviders() {
    return SUPPORTED_LOGIN_PROVIDERS;
  }
}
