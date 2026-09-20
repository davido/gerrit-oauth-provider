// Copyright (C) 2025 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.sap;

import static com.googlesource.gerrit.plugins.oauth.sap.SAPIasOAuthService.PROVIDER_NAME;

import com.google.common.base.Splitter;
import com.google.gerrit.server.config.PluginConfig;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import com.sap.cloud.security.client.DefaultHttpClientFactory;
import com.sap.cloud.security.config.ClientCredentials;
import com.sap.cloud.security.config.OAuth2ServiceConfiguration;
import com.sap.cloud.security.config.OAuth2ServiceConfigurationBuilder;
import com.sap.cloud.security.config.Service;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.validation.CombiningValidator;
import com.sap.cloud.security.token.validation.validators.JwtValidatorBuilder;
import java.util.List;

@Singleton
public class SAPIasTokenValidatorProvider implements Provider<CombiningValidator<Token>> {
  private static final String ONDEMAND_DOMAIN = ".ondemand.com";
  private static final String CLOUD_DOMAIN = ".cloud.sap";

  private final PluginConfig cfg;
  private final OAuth2ServiceConfiguration serviceConfiguration;

  @Inject
  SAPIasTokenValidatorProvider(OAuthPluginConfigFactory cfgFactory) {
    cfg = cfgFactory.create(PROVIDER_NAME);

    String rootUrl = OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL));
    List<String> rootUrlParts = Splitter.on('.').splitToList(rootUrl);
    String universeSubdomain = rootUrlParts.get(rootUrlParts.size() - 3);
    serviceConfiguration =
        OAuth2ServiceConfigurationBuilder.forService(Service.IAS)
            .withUrl(rootUrl)
            .withClientId(cfg.getString(OAuthConfigKeys.CLIENT_ID))
            .withDomains(universeSubdomain + ONDEMAND_DOMAIN, universeSubdomain + CLOUD_DOMAIN)
            .build();
  }

  @Override
  public CombiningValidator<Token> get() {
    ClientCredentials clientCredentials =
        new ClientCredentials(
            cfg.getString(OAuthConfigKeys.CLIENT_ID), cfg.getString(OAuthConfigKeys.CLIENT_SECRET));
    return JwtValidatorBuilder.getInstance(serviceConfiguration)
        .withHttpClient(new DefaultHttpClientFactory().createClient(clientCredentials))
        .build();
  }
}
