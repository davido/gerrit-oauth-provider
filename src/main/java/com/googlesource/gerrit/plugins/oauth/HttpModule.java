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

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;
import com.google.inject.servlet.ServletModule;

class HttpModule extends ServletModule {

  private final PluginConfigFactory cfgFactory;
  private final String pluginName;

  @Inject
  HttpModule(PluginConfigFactory cfgFactory, @PluginName String pluginName) {
    this.cfgFactory = cfgFactory;
    this.pluginName = pluginName;
  }

  @Override
  protected void configureServlets() {
    PluginConfig cfg =
        cfgFactory.getFromGerritConfig(pluginName + GoogleOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(GoogleOAuthService.CONFIG_SUFFIX))
          .to(GoogleOAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + GitHubOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(GitHubOAuthService.CONFIG_SUFFIX))
          .to(GitHubOAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + BitbucketOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(BitbucketOAuthService.CONFIG_SUFFIX))
          .to(BitbucketOAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + CasOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(CasOAuthService.CONFIG_SUFFIX))
          .to(CasOAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + FacebookOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(FacebookOAuthService.CONFIG_SUFFIX))
          .to(FacebookOAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + GitLabOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(GitLabOAuthService.CONFIG_SUFFIX))
          .to(GitLabOAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + LemonLDAPOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(LemonLDAPOAuthService.CONFIG_SUFFIX))
          .to(LemonLDAPOAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + DexOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(DexOAuthService.CONFIG_SUFFIX))
          .to(DexOAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + KeycloakOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(KeycloakOAuthService.CONFIG_SUFFIX))
          .to(KeycloakOAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + Office365OAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(Office365OAuthService.CONFIG_SUFFIX))
          .to(Office365OAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + AirVantageOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(AirVantageOAuthService.CONFIG_SUFFIX))
          .to(AirVantageOAuthService.class);
    }

    cfg = cfgFactory.getFromGerritConfig(pluginName + PhabricatorOAuthService.CONFIG_SUFFIX);
    if (cfg.getString(InitOAuth.CLIENT_ID) != null) {
      bind(OAuthServiceProvider.class)
          .annotatedWith(Exports.named(PhabricatorOAuthService.CONFIG_SUFFIX))
          .to(PhabricatorOAuthService.class);
    }
  }
}
