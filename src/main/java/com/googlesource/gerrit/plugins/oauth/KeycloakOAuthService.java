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
import java.io.IOException;
import org.apache.commons.codec.binary.Base64;
import org.scribe.builder.ServiceBuilder;
import org.scribe.model.Token;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KeycloakOAuthService implements OAuthServiceProvider {

  private static final Logger log = LoggerFactory.getLogger(KeycloakOAuthService.class);

  static final String CONFIG_SUFFIX = "-keycloak-oauth";
  private static final String KEYCLOAK_PROVIDER_PREFIX = "keycloak-oauth:";
  private final OAuthService service;
  private final String serviceName;

  @Inject
  KeycloakOAuthService(
      PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName + CONFIG_SUFFIX);
    String canonicalWebUrl = CharMatcher.is('/').trimTrailingFrom(urlProvider.get()) + "/";

    String rootUrl = cfg.getString(InitOAuth.ROOT_URL);
    String realm = cfg.getString(InitOAuth.REALM);
    serviceName = cfg.getString(InitOAuth.SERVICE_NAME, "Keycloak OAuth2");

    service =
        new ServiceBuilder()
            .provider(new KeycloakApi(rootUrl, realm))
            .apiKey(cfg.getString(InitOAuth.CLIENT_ID))
            .apiSecret(cfg.getString(InitOAuth.CLIENT_SECRET))
            .scope("openid")
            .callback(canonicalWebUrl + "oauth")
            .build();
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

    JsonObject claimObject = claimJson.getAsJsonObject();
    if (log.isDebugEnabled()) {
      log.debug("Claim object: {}", claimObject);
    }
    JsonElement usernameElement = claimObject.get("preferred_username");
    JsonElement emailElement = claimObject.get("email");
    JsonElement nameElement = claimObject.get("name");
    if (usernameElement == null || usernameElement.isJsonNull()) {
      throw new IOException("Response doesn't contain preferred_username field");
    }
    if (emailElement == null || emailElement.isJsonNull()) {
      throw new IOException("Response doesn't contain email field");
    }
    if (nameElement == null || nameElement.isJsonNull()) {
      throw new IOException("Response doesn't contain name field");
    }
    String username = usernameElement.getAsString();
    String email = emailElement.getAsString();
    String name = nameElement.getAsString();

    return new OAuthUserInfo(
        KEYCLOAK_PROVIDER_PREFIX + username /*externalId*/,
        username /*username*/,
        email /*email*/,
        name /*displayName*/,
        null /*claimedIdentity*/);
  }

  @Override
  public OAuthToken getAccessToken(OAuthVerifier rv) {
    Verifier vi = new Verifier(rv.getValue());
    Token to = service.getAccessToken(null, vi);
    return new OAuthToken(to.getToken(), to.getSecret(), to.getRawResponse());
  }

  @Override
  public String getAuthorizationUrl() {
    return service.getAuthorizationUrl(null);
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
