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

package com.googlesource.gerrit.plugins.google.oauth.provider;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.server.OutputFormat;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.scribe.builder.ServiceBuilder;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;

import java.io.IOException;

@Singleton
class GoogleOAuthService implements OAuthServiceProvider {
  private static final String PROTECTED_RESOURCE_URL =
      "https://www.googleapis.com/oauth2/v2/userinfo?alt=json";
  private static final String SCOPE =
      "https://mail.google.com/ https://www.googleapis.com/auth/userinfo.email";
  private final OAuthService service;

  @Inject
  GoogleOAuthService(PluginConfigFactory cfgFactory,
      @PluginName String pluginName) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName);
    service = new ServiceBuilder()
        .provider(Google2Api.class)
        .apiKey(cfg.getString("client-id"))
        .apiSecret(cfg.getString("client-secret"))
        .callback(cfg.getString("callback"))
        .scope(SCOPE)
        .build();
  }

  @Override
  public OAuthToken getRequestToken() {
    throw new IllegalStateException();
  }

  @Override
  public String getUsername(OAuthToken token) throws IOException {
    OAuthRequest request = new OAuthRequest(Verb.GET, PROTECTED_RESOURCE_URL);
    Token t =
        new Token(token.getToken(), token.getSecret(), token.getRaw());
    service.signRequest(t, request);
    Response response = request.send();
    JsonElement userJson =
        OutputFormat.JSON.newGson().fromJson(response.getBody(),
            JsonElement.class);
    if (userJson.isJsonObject()) {
      JsonObject jsonObject = userJson.getAsJsonObject();
      JsonElement jsonElement = jsonObject.getAsJsonObject().get("name");
      if (jsonElement != null) {
        return jsonElement.getAsString();
      } else {
        throw new IOException(String.format(
            "Invalid JSON '%s': cannot find login field", userJson));
      }
    } else {
      throw new IOException(String.format(
          "Invalid JSON '%s': not a JSON Object", userJson));
    }
  }

  @Override
  public OAuthToken getAccessToken(OAuthToken rt,
      OAuthVerifier rv) {
    Token ti = null;
    if (rt != null) {
      ti = new Token(rt.getToken(), rt.getSecret(), rt.getRaw());
    }
    Verifier vi = new Verifier(rv.getValue());
    Token to = service.getAccessToken(ti, vi);
    OAuthToken result = new OAuthToken(to.getToken(),
        to.getSecret(), to.getRawResponse());
    return result;
  }

  @Override
  public String getAuthorizationUrl(OAuthToken rt) {
    Token ti = null;
    if (rt != null) {
      ti = new Token(rt.getToken(), rt.getSecret(), rt.getRaw());
    }
    return service.getAuthorizationUrl(ti);
  }

  @Override
  public String getVersion() {
    return service.getVersion();
  }

  @Override
  public String getName() {
    return "Google OAuth2";
  }
}
