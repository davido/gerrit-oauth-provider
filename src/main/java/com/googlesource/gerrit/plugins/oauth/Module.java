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
import com.google.gerrit.server.account.AccountExternalIdCreator;
import com.google.gerrit.server.account.externalids.ExternalIdFactory;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCache;
import com.googlesource.gerrit.plugins.oauth.sap.SAPIasModule;
import com.googlesource.gerrit.plugins.oauth.sap.SAPIasOAuthLoginProvider;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Config;

public class Module extends AbstractModule {
  private static final String OAUTH_SECTION_SUFFIX = "-oauth";
  private static final List<SupportedLoginProvider> SUPPORTED_LOGIN_PROVIDERS =
      List.of(
          // SAP is grandfathered on; newer providers default off (opt-in via enable-git-over-http).
          new SupportedLoginProvider(SAPIasOAuthLoginProvider.class, SAPIasModule::new, true));

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
    bind(OAuth20ServiceFactory.class);
    bind(HttpOAuthClientFactory.class);
    install(OAuthTokenValidationCache.module());
    bindExternalIdCreators();
    bindOAuthProviders();
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
