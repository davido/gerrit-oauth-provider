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

import com.google.common.base.CharMatcher;
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
import com.google.inject.Singleton;
import org.scribe.builder.ServiceBuilder;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;
import org.slf4j.Logger;

import java.io.IOException;

import static com.google.gerrit.server.OutputFormat.JSON;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.slf4j.LoggerFactory.getLogger;

@Singleton
public class BitbucketOAuthService implements OAuthServiceProvider {
  private static final Logger log = getLogger(BitbucketOAuthService.class);
  static final String CONFIG_SUFFIX = "-bitbucket-oauth";
  private static final String PROTECTED_RESOURCE_URL =
      "https://bitbucket.org/api/1.0/user/";
  private final OAuthService service;

  @Inject
  BitbucketOAuthService(PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg =
        cfgFactory.getFromGerritConfig(pluginName + CONFIG_SUFFIX);

    String canonicalWebUrl =
        CharMatcher.is('/').trimTrailingFrom(urlProvider.get()) + "/";

    service = new ServiceBuilder().provider(BitbucketApi.class)
        .apiKey(cfg.getString(InitOAuth.CLIENT_ID))
        .apiSecret(cfg.getString(InitOAuth.CLIENT_SECRET))
        .callback(canonicalWebUrl + "oauth")
        .build();
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    OAuthRequest request = new OAuthRequest(Verb.GET, PROTECTED_RESOURCE_URL);
    Token t = new Token(token.getToken(), token.getSecret(), token.getRaw());
    service.signRequest(t, request);
    Response response = request.send();
    if (response.getCode() != SC_OK) {
      throw new IOException(String.format("Status %s (%s) for request %s",
          response.getCode(), response.getBody(), request.getUrl()));
    }
    JsonElement userJson =
        JSON.newGson().fromJson(response.getBody(), JsonElement.class);
    if (log.isDebugEnabled()) {
      log.debug("User info response: {}", response.getBody());
    }
    if (userJson.isJsonObject()) {
      JsonObject jsonObject = userJson.getAsJsonObject();
      JsonObject userObject = jsonObject.getAsJsonObject("user");
      if (userObject == null || userObject.isJsonNull()) {
        throw new IOException("Response doesn't contain 'user' field");
      }
      JsonElement usernameElement = userObject.get("username");
      String username = usernameElement.getAsString();

      JsonElement displayName = jsonObject.get("display_name");
      return new OAuthUserInfo(username, username, null,
          displayName == null || displayName.isJsonNull() ? null
              : displayName.getAsString(),
          null);
    }

    throw new IOException(
        String.format("Invalid JSON '%s': not a JSON Object", userJson));
  }

  @Override
  public OAuthToken getAccessToken(OAuthVerifier rv) {
    Verifier vi = new Verifier(rv.getValue());
    Token to = service.getAccessToken(null, vi);
    return new OAuthToken(to.getToken(), to.getSecret(), null);
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
    return "Bitbucket OAuth2";
  }
}
