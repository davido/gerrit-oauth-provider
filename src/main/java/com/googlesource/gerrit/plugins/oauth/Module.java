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

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.account.AccountExternalIdCreator;
import com.google.gerrit.server.account.externalids.ExternalIdFactory;
import com.google.gerrit.server.auth.oauth.OAuthTokenRevokedListener;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.googlesource.gerrit.plugins.oauth.airvantage.AirVantageOAuthService;
import com.googlesource.gerrit.plugins.oauth.azure.AzureActiveDirectoryService;
import com.googlesource.gerrit.plugins.oauth.azure.AzureModule;
import com.googlesource.gerrit.plugins.oauth.azure.AzureOAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderConfig;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCache;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCacheCleaner;
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
import com.googlesource.gerrit.plugins.oauth.sap.SAPIasModule;
import com.googlesource.gerrit.plugins.oauth.sap.SAPIasOAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.sap.SAPIasOAuthService;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Config;

public class Module extends AbstractModule {

  private static final String OAUTH_SECTION_SUFFIX = "-oauth";
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

  private final List<String> configuredProviders;
  private final ExternalIdFactory externalIdFactory;
  private final String pluginName;
  private final Config cfg;
  private final List<SupportedLoginProvider> supportedLoginProviders;

  @Inject
  public Module(
      @GerritServerConfig Config config,
      @PluginName String pluginName,
      ExternalIdFactory externalIdFactory) {
    this(config, pluginName, externalIdFactory, SUPPORTED_LOGIN_PROVIDERS);
  }

  Module(
      Config config,
      String pluginName,
      ExternalIdFactory externalIdFactory,
      List<SupportedLoginProvider> supportedLoginProviders) {
    this.pluginName = pluginName;
    this.configuredProviders =
        config.getSubsections("plugin").stream()
            .filter(s -> s.startsWith(pluginName + "-"))
            .filter(s -> s.endsWith(OAUTH_SECTION_SUFFIX))
            .map(
                s ->
                    s.substring(
                        pluginName.length() + 1, s.length() - OAUTH_SECTION_SUFFIX.length()))
            .sorted()
            .toList();
    this.externalIdFactory = externalIdFactory;
    this.cfg = config;
    this.supportedLoginProviders = supportedLoginProviders;
  }

  @Override
  protected void configure() {
    bind(OAuthPluginConfigFactory.class);
    bind(HttpOAuthClientFactory.class);
    install(OAuthTokenValidationCache.module());
    // Drop cached Git-over-HTTP validations when core revokes a token, so a revoked token stops
    // being accepted immediately instead of lingering until the validation entry's TTL.
    DynamicSet.bind(binder(), OAuthTokenRevokedListener.class)
        .to(OAuthTokenValidationCacheCleaner.class);
    bindServiceProviders();
    bindExternalIdCreators();
    bindOAuthProviders();
  }

  /** All OAuth service-provider implementations, keyed by their configured section name. */
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

  /**
   * Registers each configured provider's {@link OAuthServiceProvider} as an {@code @Exports} in
   * this (sys) injector. Core declares {@code DynamicMap<OAuthServiceProvider>} in the sys injector
   * ({@code GerritGlobalModule}), so these contributions populate the map that browser login, the
   * core {@code oauth-token} SSH command, and {@code OAuthTokenRefresher} all read.
   */
  private void bindServiceProviders() {
    for (Class<? extends OAuthServiceProvider> cls : ALL_SERVICE_PROVIDERS) {
      String name = cls.getAnnotation(OAuthServiceProviderConfig.class).name();
      if (hasClientId(name)) {
        bind(OAuthServiceProvider.class)
            .annotatedWith(Exports.named(OAuthServiceProviderExternalIdScheme.create(name)))
            .to(cls);
      }
    }
  }

  private void bindExternalIdCreators() {
    for (String provider : configuredProviders) {
      bind(AccountExternalIdCreator.class)
          .annotatedWith(Exports.named(provider))
          .toInstance(
              new OAuthExternalIdCreator(
                  externalIdFactory, OAuthServiceProviderExternalIdScheme.create(provider)));
    }
  }

  private void bindOAuthProviders() {
    List<SupportedLoginProvider> gitHttpProviders =
        supportedLoginProviders.stream()
            .filter(p -> hasClientId(p.name()))
            .filter(this::isGitOverHttpEnabled)
            .collect(Collectors.toList());

    if (gitHttpProviders.size() > 1) {
      String names =
          gitHttpProviders.stream()
              .map(SupportedLoginProvider::name)
              .collect(Collectors.joining(", "));
      throw new ProvisionException(
          "Multiple OAuth providers configured that support Git-over-HTTP ("
              + names
              + "). Exactly one provider that supports Git-over-HTTP must be configured.");
    }

    if (!gitHttpProviders.isEmpty()) {
      install(gitHttpProviders.get(0).module());
    } else {
      bindDisabledOAuthProvider();
    }
  }

  private boolean hasClientId(String loginProviderName) {
    String cfgSuffix = OAuthPluginConfigFactory.getConfigSuffix(loginProviderName);
    return cfg.getString("plugin", pluginName + cfgSuffix, OAuthConfigKeys.CLIENT_ID) != null;
  }

  /** Reads {@code enable-git-over-http} for the provider, falling back to its default. */
  private boolean isGitOverHttpEnabled(SupportedLoginProvider provider) {
    String cfgSuffix = OAuthPluginConfigFactory.getConfigSuffix(provider.name());
    return cfg.getBoolean(
        "plugin",
        pluginName + cfgSuffix,
        OAuthConfigKeys.ENABLE_GIT_OVER_HTTP,
        provider.defaultGitOverHttp());
  }

  private void bindDisabledOAuthProvider() {
    bind(OAuthLoginProvider.class)
        .annotatedWith(Exports.named(pluginName))
        .to(DisabledOAuthLoginProvider.class);
  }
}
