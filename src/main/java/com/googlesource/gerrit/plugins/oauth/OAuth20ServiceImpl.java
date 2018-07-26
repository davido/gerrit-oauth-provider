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

import static org.slf4j.LoggerFactory.getLogger;

import org.scribe.builder.api.DefaultApi20;
import org.scribe.model.OAuthConfig;
import org.scribe.model.OAuthConstants;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;
import org.slf4j.Logger;

/** TODO(gildur): remove when updating to newer scribe lib */
final class OAuth20ServiceImpl implements OAuthService {
  private static final Logger log = getLogger(OAuth20ServiceImpl.class);

  private static final String VERSION = "2.0";

  private static final String GRANT_TYPE = "grant_type";
  private static final String GRANT_TYPE_VALUE = "authorization_code";

  private final DefaultApi20 api;
  private final OAuthConfig config;

  /**
   * Default constructor
   *
   * @param api OAuth2.0 api information
   * @param config OAuth 2.0 configuration param object
   */
  public OAuth20ServiceImpl(DefaultApi20 api, OAuthConfig config) {
    this.api = api;
    this.config = config;
  }

  @Override
  public Token getAccessToken(Token requestToken, Verifier verifier) {
    OAuthRequest request = new OAuthRequest(api.getAccessTokenVerb(), api.getAccessTokenEndpoint());
    request.addBodyParameter(OAuthConstants.CLIENT_ID, config.getApiKey());
    request.addBodyParameter(OAuthConstants.CLIENT_SECRET, config.getApiSecret());
    request.addBodyParameter(OAuthConstants.CODE, verifier.getValue());
    request.addBodyParameter(OAuthConstants.REDIRECT_URI, config.getCallback());
    if (config.hasScope()) {
      request.addBodyParameter(OAuthConstants.SCOPE, config.getScope());
    }
    request.addBodyParameter(GRANT_TYPE, GRANT_TYPE_VALUE);
    if (log.isDebugEnabled()) {
      log.error("Access token request: {}", request);
    }
    Response response = request.send();
    if (log.isDebugEnabled()) {
      log.error("Access token response: {}", response.getBody());
    }
    return api.getAccessTokenExtractor().extract(response.getBody());
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
  public void signRequest(Token accessToken, OAuthRequest request) {
    request.addQuerystringParameter(OAuthConstants.ACCESS_TOKEN, accessToken.getToken());
  }

  @Override
  public String getAuthorizationUrl(Token requestToken) {
    return api.getAuthorizationUrl(config);
  }
}
