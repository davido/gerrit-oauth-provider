// Copyright (C) 2026 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.base;

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.account.AccountExternalIdCreator;
import com.google.gerrit.server.account.externalids.ExternalIdFactory;
import com.google.gerrit.server.auth.oauth.OAuthTokenRevokedListener;
import com.google.inject.AbstractModule;
import com.google.inject.ProvisionException;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Config;

/**
 * Provider-agnostic wiring shared by every OAuth plugin artifact.
 *
 * <p>Subclasses declare which providers a given artifact bundles by returning them from {@link
 * #serviceProviders()} and {@link #loginProviders()}. The all-inclusive {@code oauth} plugin
 * returns every provider; a single-provider artifact (e.g. {@code oauth-google}) returns only its
 * own. All config-driven selection logic lives here, so provider composition is the only thing that
 * varies between artifacts.
 */
public abstract class AbstractOAuthModule extends AbstractModule {

  private static final String OAUTH_SECTION_SUFFIX = "-oauth";

  protected final Config cfg;
  protected final String pluginName;
  private final ExternalIdFactory externalIdFactory;
  private final List<String> configuredProviders;

  protected AbstractOAuthModule(
      Config config, String pluginName, ExternalIdFactory externalIdFactory) {
    this.cfg = config;
    this.pluginName = pluginName;
    this.externalIdFactory = externalIdFactory;
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
  }

  /** OAuth service-provider implementations this artifact bundles. */
  protected abstract List<Class<? extends OAuthServiceProvider>> serviceProviders();

  /** Git-over-HTTP-capable login providers this artifact bundles. */
  protected abstract List<SupportedLoginProvider> loginProviders();

  @Override
  protected final void configure() {
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

  /**
   * Registers each configured provider's {@link OAuthServiceProvider} as an {@code @Exports} in
   * this (sys) injector. Core declares {@code DynamicMap<OAuthServiceProvider>} in the sys injector
   * ({@code GerritGlobalModule}), so these contributions populate the map that browser login, the
   * core {@code oauth-token} SSH command, and {@code OAuthTokenRefresher} all read.
   */
  private void bindServiceProviders() {
    for (Class<? extends OAuthServiceProvider> cls : serviceProviders()) {
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
        loginProviders().stream()
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
