// Copyright (C) 2016 The Android Open Source Project
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
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.common.base.CharMatcher;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
class CasOAuthService implements OAuthServiceProvider {
  private static final Logger log = LoggerFactory.getLogger(CasOAuthService.class);
  static final String CONFIG_SUFFIX = "-cas-oauth";
  private static final String CAS_PROVIDER_PREFIX = "cas-oauth:";
  private static final String PROTECTED_RESOURCE_URL = "%s/oauth2.0/profile";

  private final String rootUrl;
  private final boolean fixLegacyUserId;
  private final OAuth20Service service;

  @Inject
  CasOAuthService(
      PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName + CONFIG_SUFFIX);
    rootUrl = cfg.getString(InitOAuth.ROOT_URL);
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
    String canonicalWebUrl = CharMatcher.is('/').trimTrailingFrom(urlProvider.get()) + "/";
    fixLegacyUserId = cfg.getBoolean(InitOAuth.FIX_LEGACY_USER_ID, false);
    service =
        new ServiceBuilder(cfg.getString(InitOAuth.CLIENT_ID))
            .apiSecret(cfg.getString(InitOAuth.CLIENT_SECRET))
            .callback(canonicalWebUrl + "oauth")
            .build(new CasApi(rootUrl));
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    OAuthRequest request =
        new OAuthRequest(Verb.GET, String.format(PROTECTED_RESOURCE_URL, rootUrl));
    OAuth2AccessToken t = new OAuth2AccessToken(token.getToken(), token.getRaw());
    service.signRequest(t, request);

    try (Response response = service.execute(request)) {
      if (response.getCode() != HttpServletResponse.SC_OK) {
        throw new IOException(
            String.format(
                "Status %s (%s) for request %s",
                response.getCode(), response.getBody(), request.getUrl()));
      }

      if (log.isDebugEnabled()) {
        log.debug("User info response: {}", response.getBody());
      }

      JsonElement userJson = JSON.newGson().fromJson(response.getBody(), JsonElement.class);
      if (!userJson.isJsonObject()) {
        throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
      }
      JsonObject jsonObject = userJson.getAsJsonObject();

      JsonElement id = jsonObject.get("id");
      if (id == null || id.isJsonNull()) {
        throw new IOException(String.format("CAS response missing id: %s", response.getBody()));
      }

      JsonElement attrListJson = jsonObject.get("attributes");
      if (attrListJson == null) {
        throw new IOException(
            String.format("CAS response missing attributes: %s", response.getBody()));
      }

      String email = null, name = null, login = null;
      if (attrListJson.isJsonArray()) {
        // It is possible for CAS to be configured to not return any attributes (email, name,
        // login),
        // in which case,
        // CAS returns an empty JSON object "attributes":{}, rather than "null" or an empty JSON
        // array
        // "attributes": []

        JsonArray attrJson = attrListJson.getAsJsonArray();
        for (JsonElement elem : attrJson) {
          if (elem == null || !elem.isJsonObject()) {
            throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", elem));
          }
          JsonObject obj = elem.getAsJsonObject();

          String property = getStringElement(obj, "email");
          if (property != null) email = property;
          property = getStringElement(obj, "name");
          if (property != null) name = property;
          property = getStringElement(obj, "login");
          if (property != null) login = property;
        }
      }

      return new OAuthUserInfo(
          CAS_PROVIDER_PREFIX + id.getAsString(),
          login,
          email,
          name,
          fixLegacyUserId ? id.getAsString() : null);
    } catch (ExecutionException | InterruptedException e) {
      throw new RuntimeException("Cannot retrieve user info resource", e);
    }
  }

  private String getStringElement(JsonObject o, String name) {
    JsonElement elem = o.get(name);
    if (elem == null || elem.isJsonNull()) return null;

    return elem.getAsString();
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
    return "Generic CAS OAuth2";
  }
}
