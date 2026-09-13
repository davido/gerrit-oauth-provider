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

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.oauth.airvantage.AirVantageOAuthService;
import com.googlesource.gerrit.plugins.oauth.azure.AzureActiveDirectoryService;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
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

class HttpModule extends AbstractModule {
  private final OAuthPluginConfigFactory cfgFactory;

  @Inject
  HttpModule(OAuthPluginConfigFactory cfgFactory) {
    this.cfgFactory = cfgFactory;
  }

  @Override
  protected void configure() {
    install(new OAuthServiceModule(cfgFactory, AirVantageOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, AzureActiveDirectoryService.class));
    install(new OAuthServiceModule(cfgFactory, BitbucketOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, CasOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, DexOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, DiscoveryOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, FacebookOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, GitHubOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, GitLabOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, GoogleOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, KeycloakOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, PhabricatorOAuthService.class));
    install(new OAuthServiceModule(cfgFactory, SAPIasOAuthService.class));
  }
}
