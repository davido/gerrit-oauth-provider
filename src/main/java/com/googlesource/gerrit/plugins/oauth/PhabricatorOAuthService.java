// Copyright (C) 2020 The Android Open Source Project
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
class PhabricatorOAuthService implements OAuthServiceProvider {
  private static final Logger log = LoggerFactory.getLogger(PhabricatorOAuthService.class);
  static final String CONFIG_SUFFIX = "-phabricator-oauth";
  private static final String PHABRICATOR_PROVIDER_PREFIX = "phabricator-oauth:";
  private static final String PROTECTED_RESOURCE_URL = "%s/api/user.whoami";
  private final String rootUrl;
  private final OAuth20Service service;

  @Inject
  PhabricatorOAuthService(
      PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName + CONFIG_SUFFIX);
    String canonicalWebUrl = CharMatcher.is('/').trimTrailingFrom(urlProvider.get()) + "/";
    rootUrl = cfg.getString(InitOAuth.ROOT_URL);
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
    this.service =
        new ServiceBuilder(cfg.getString(InitOAuth.CLIENT_ID))
            .apiSecret(cfg.getString(InitOAuth.CLIENT_SECRET))
            .callback(canonicalWebUrl + "oauth")
            .build(new PhabricatorApi(rootUrl));
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: canonicalWebUrl={}", canonicalWebUrl);
    }
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    OAuthRequest request =
        new OAuthRequest(Verb.GET, String.format(PROTECTED_RESOURCE_URL, rootUrl));
    OAuth2AccessToken t = new OAuth2AccessToken(token.getToken(), token.getRaw());
    service.signRequest(t, request);

    JsonElement userJson = null;
    try (Response response = service.execute(request)) {
      if (response.getCode() != HttpServletResponse.SC_OK) {
        throw new IOException(
            String.format(
                "Status %s (%s) for request %s",
                response.getCode(), response.getBody(), request.getUrl()));
      }
      userJson = JSON.newGson().fromJson(response.getBody(), JsonElement.class);
      if (log.isDebugEnabled()) {
        log.debug("User info response: {}", response.getBody());
      }
      if (userJson.isJsonObject()) {
        JsonObject jsonObject = userJson.getAsJsonObject();
        JsonElement jsonResult = jsonObject.get("result");
        if (jsonResult == null) {
          throw new IOException("Response doesn't contain result field");
        }
        JsonObject resultObject = jsonResult.getAsJsonObject();
        JsonElement id = resultObject.get("phid");
        if (id == null || id.isJsonNull()) {
          throw new IOException("Response doesn't contain id field");
        }
        JsonElement email = resultObject.get("primaryEmail");
        JsonElement name = resultObject.get("realName");
        JsonElement username = resultObject.get("userName");
        String login = null;

        if (!username.isJsonNull()) {
          login = username.getAsString();
        }
        return new OAuthUserInfo(
            PHABRICATOR_PROVIDER_PREFIX + id.getAsString() /*externalId*/,
            login /*username*/,
            email == null || email.isJsonNull() ? null : email.getAsString() /*email*/,
            name == null || name.isJsonNull() ? null : name.getAsString() /*displayName*/,
            null);
      }
    } catch (ExecutionException | InterruptedException e) {
      throw new RuntimeException("Cannot retrieve user info resource", e);
    }

    throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
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
    return "Phabricator OAuth2";
  }
}
