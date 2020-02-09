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

import static com.google.gerrit.server.OutputFormat.JSON;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class DexOAuthService implements OAuthServiceProvider {
  private static final Logger log = LoggerFactory.getLogger(DexOAuthService.class);

  static final String CONFIG_SUFFIX = "-dex-oauth";
  private static final String DEX_PROVIDER_PREFIX = "dex-oauth:";
  private final OAuth20Service service;
  private final String rootUrl;
  private final String domain;
  private final String serviceName;

  @Inject
  DexOAuthService(
      PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName + CONFIG_SUFFIX);
    String canonicalWebUrl = CharMatcher.is('/').trimTrailingFrom(urlProvider.get()) + "/";

    rootUrl = cfg.getString(InitOAuth.ROOT_URL);
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
    domain = cfg.getString(InitOAuth.DOMAIN, null);
    serviceName = cfg.getString(InitOAuth.SERVICE_NAME, "Dex OAuth2");

    service =
        new ServiceBuilder(cfg.getString(InitOAuth.CLIENT_ID))
            .apiSecret(cfg.getString(InitOAuth.CLIENT_SECRET))
            .defaultScope("openid profile email offline_access")
            .callback(canonicalWebUrl + "oauth")
            .build(new DexApi(rootUrl));
  }

  private String parseJwt(String input) {
    String[] parts = input.split("\\.");
    Preconditions.checkState(parts.length == 3);
    Preconditions.checkNotNull(parts[1]);
    return new String(Base64.decodeBase64(parts[1]));
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    JsonElement tokenJson = JSON.newGson().fromJson(token.getRaw(), JsonElement.class);
    JsonObject tokenObject = tokenJson.getAsJsonObject();
    JsonElement id_token = tokenObject.get("id_token");

    JsonElement claimJson =
        JSON.newGson().fromJson(parseJwt(id_token.getAsString()), JsonElement.class);

    // Dex does not support basic profile currently (2017-09), extracting info
    // from access token claim

    JsonObject claimObject = claimJson.getAsJsonObject();
    JsonElement emailElement = claimObject.get("email");
    JsonElement nameElement = claimObject.get("name");
    if (emailElement == null || emailElement.isJsonNull()) {
      throw new IOException("Response doesn't contain email field");
    }
    if (nameElement == null || nameElement.isJsonNull()) {
      throw new IOException("Response doesn't contain name field");
    }
    String email = emailElement.getAsString();
    String name = nameElement.getAsString();
    String username = email;
    if (domain != null && domain.length() > 0) {
      username = email.replace("@" + domain, "");
    }

    return new OAuthUserInfo(
        DEX_PROVIDER_PREFIX + email /*externalId*/,
        username /*username*/,
        email /*email*/,
        name /*displayName*/,
        null /*claimedIdentity*/);
  }

  @Override
  public OAuthToken getAccessToken(OAuthVerifier rv) {
    try {
      OAuth2AccessToken accessToken = service.getAccessToken(rv.getValue());
      return new OAuthToken(
          accessToken.getAccessToken(), accessToken.getTokenType(), accessToken.getRawResponse());
    } catch (InterruptedException | ExecutionException | IOException e) {
      String msg = "Cannot retrieve access token";
      log.error(msg, e);
      throw new RuntimeException(msg, e);
    }
  }

  @Override
  public String getAuthorizationUrl() {
    return service.getAuthorizationUrl();
  }

  @Override
  public String getVersion() {
    return service.getVersion();
  }

  @Override
  public String getName() {
    return serviceName;
  }
}
