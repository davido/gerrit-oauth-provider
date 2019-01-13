// Copyright (C) 2018 The Android Open Source Project
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

import static com.google.gerrit.json.OutputFormat.JSON;

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
import java.io.IOException;
import javax.servlet.http.HttpServletResponse;
import org.scribe.builder.ServiceBuilder;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
class Office365OAuthService implements OAuthServiceProvider {
  private static final Logger log = LoggerFactory.getLogger(Office365OAuthService.class);
  static final String CONFIG_SUFFIX = "-office365-oauth";
  private static final String OFFICE365_PROVIDER_PREFIX = "office365-oauth:";
  private static final String PROTECTED_RESOURCE_URL = "https://graph.microsoft.com/v1.0/me";
  private static final String SCOPE =
      "openid offline_access https://graph.microsoft.com/user.readbasic.all";
  private final OAuthService service;
  private final String canonicalWebUrl;
  private final boolean useEmailAsUsername;

  @Inject
  Office365OAuthService(
      PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName + CONFIG_SUFFIX);
    this.canonicalWebUrl = CharMatcher.is('/').trimTrailingFrom(urlProvider.get()) + "/";
    this.useEmailAsUsername = cfg.getBoolean(InitOAuth.USE_EMAIL_AS_USERNAME, false);
    this.service =
        new ServiceBuilder()
            .provider(Office365Api.class)
            .apiKey(cfg.getString(InitOAuth.CLIENT_ID))
            .apiSecret(cfg.getString(InitOAuth.CLIENT_SECRET))
            .callback(canonicalWebUrl + "oauth")
            .scope(SCOPE)
            .build();
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: canonicalWebUrl={}", canonicalWebUrl);
      log.debug("OAuth2: scope={}", SCOPE);
      log.debug("OAuth2: useEmailAsUsername={}", useEmailAsUsername);
    }
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    OAuthRequest request = new OAuthRequest(Verb.GET, PROTECTED_RESOURCE_URL);
    request.addHeader("Accept", "*/*");
    request.addHeader("Authorization", "Bearer " + token.getToken());
    Response response = request.send();
    if (response.getCode() != HttpServletResponse.SC_OK) {
      throw new IOException(
          String.format(
              "Status %s (%s) for request %s",
              response.getCode(), response.getBody(), request.getUrl()));
    }
    JsonElement userJson = JSON.newGson().fromJson(response.getBody(), JsonElement.class);
    if (log.isDebugEnabled()) {
      log.debug("User info response: {}", response.getBody());
    }
    if (userJson.isJsonObject()) {
      JsonObject jsonObject = userJson.getAsJsonObject();
      JsonElement id = jsonObject.get("id");
      if (id == null || id.isJsonNull()) {
        throw new IOException("Response doesn't contain id field");
      }
      JsonElement email = jsonObject.get("mail");
      JsonElement name = jsonObject.get("displayName");
      String login = null;

      if (useEmailAsUsername && !email.isJsonNull()) {
        login = email.getAsString().split("@")[0];
      }
      return new OAuthUserInfo(
          OFFICE365_PROVIDER_PREFIX + id.getAsString() /*externalId*/,
          login /*username*/,
          email == null || email.isJsonNull() ? null : email.getAsString() /*email*/,
          name == null || name.isJsonNull() ? null : name.getAsString() /*displayName*/,
          null);
    }

    throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
  }

  @Override
  public OAuthToken getAccessToken(OAuthVerifier rv) {
    Verifier vi = new Verifier(rv.getValue());
    Token to = service.getAccessToken(null, vi);
    OAuthToken result = new OAuthToken(to.getToken(), to.getSecret(), to.getRawResponse());
    return result;
  }

  @Override
  public String getAuthorizationUrl() {
    String url = service.getAuthorizationUrl(null);
    return url;
  }

  @Override
  public String getVersion() {
    return service.getVersion();
  }

  @Override
  public String getName() {
    return "Office365 OAuth2";
  }
}
