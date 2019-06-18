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

import static com.google.gerrit.json.OutputFormat.JSON;
import static java.lang.String.format;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.scribe.model.OAuthConstants.ACCESS_TOKEN;
import static org.scribe.model.OAuthConstants.CODE;

import com.google.common.io.BaseEncoding;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.scribe.builder.api.DefaultApi20;
import org.scribe.exceptions.OAuthException;
import org.scribe.extractors.AccessTokenExtractor;
import org.scribe.model.OAuthConfig;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;

public class BitbucketApi extends DefaultApi20 {

  private static final String AUTHORIZE_URL =
      "https://bitbucket.org/site/oauth2/authorize?client_id=%s&response_type=code";
  private static final String ACCESS_TOKEN_ENDPOINT =
      "https://bitbucket.org/site/oauth2/access_token";

  public BitbucketApi() {}

  @Override
  public String getAuthorizationUrl(OAuthConfig config) {
    return format(AUTHORIZE_URL, config.getApiKey());
  }

  @Override
  public String getAccessTokenEndpoint() {
    return ACCESS_TOKEN_ENDPOINT;
  }

  @Override
  public Verb getAccessTokenVerb() {
    return Verb.POST;
  }

  @Override
  public OAuthService createService(OAuthConfig config) {
    return new BitbucketOAuthService(this, config);
  }

  @Override
  public AccessTokenExtractor getAccessTokenExtractor() {
    return new BitbucketTokenExtractor();
  }

  private static final class BitbucketOAuthService implements OAuthService {
    private static final String VERSION = "2.0";

    private static final String GRANT_TYPE = "grant_type";
    private static final String GRANT_TYPE_VALUE = "authorization_code";

    private final DefaultApi20 api;
    private final OAuthConfig config;

    private BitbucketOAuthService(DefaultApi20 api, OAuthConfig config) {
      this.config = config;
      this.api = api;
    }

    @Override
    public Token getAccessToken(Token token, Verifier verifier) {
      OAuthRequest request =
          new OAuthRequest(api.getAccessTokenVerb(), api.getAccessTokenEndpoint());
      request.addHeader("Authorization", prepareAuthorizationHeaderValue());
      request.addBodyParameter(GRANT_TYPE, GRANT_TYPE_VALUE);
      request.addBodyParameter(CODE, verifier.getValue());
      Response response = request.send();
      if (response.getCode() == SC_OK) {
        Token t = api.getAccessTokenExtractor().extract(response.getBody());
        return new Token(t.getToken(), config.getApiSecret());
      }

      throw new OAuthException(
          String.format(
              "Error response received: %s, HTTP status: %s",
              response.getBody(), response.getCode()));
    }

    private String prepareAuthorizationHeaderValue() {
      String value = String.format("%s:%s", config.getApiKey(), config.getApiSecret());
      String valueBase64 = BaseEncoding.base64().encode(value.getBytes());
      return String.format("Basic %s", valueBase64);
    }

    @Override
    public Token getRequestToken() {
      throw new UnsupportedOperationException(
          "Unsupported operation, please use 'getAuthorizationUrl' and redirect your users there");
    }

    @Override
    public String getVersion() {
      return VERSION;
    }

    @Override
    public void signRequest(Token token, OAuthRequest request) {
      request.addQuerystringParameter(ACCESS_TOKEN, token.getToken());
    }

    @Override
    public String getAuthorizationUrl(Token token) {
      return api.getAuthorizationUrl(config);
    }
  }

  private static final class BitbucketTokenExtractor implements AccessTokenExtractor {

    @Override
    public Token extract(String response) {
      JsonElement json = JSON.newGson().fromJson(response, JsonElement.class);
      if (json.isJsonObject()) {
        JsonObject jsonObject = json.getAsJsonObject();
        JsonElement id = jsonObject.get(ACCESS_TOKEN);
        if (id == null || id.isJsonNull()) {
          throw new OAuthException("Response doesn't contain 'access_token' field");
        }
        JsonElement accessToken = jsonObject.get(ACCESS_TOKEN);
        return new Token(accessToken.getAsString(), "");
      }

      throw new OAuthException(String.format("Invalid JSON '%s': not a JSON Object", json));
    }
  }
}
