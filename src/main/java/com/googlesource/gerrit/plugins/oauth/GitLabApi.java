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

import org.scribe.builder.api.DefaultApi20;
import org.scribe.exceptions.OAuthException;
import org.scribe.extractors.AccessTokenExtractor;
import org.scribe.model.*;
import org.scribe.oauth.OAuthService;

import org.scribe.utils.Preconditions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

public class GitLabApi extends DefaultApi20 {
  private static final String AUTHORIZE_URL =
      "%s/oauth/authorize?client_id=%s&response_type=code&redirect_uri=%s";

  private final String rootUrl;

  public GitLabApi(String rootUrl) {
    this.rootUrl = rootUrl;
  }

  @Override
  public String getAuthorizationUrl(OAuthConfig config) {
    return String.format(AUTHORIZE_URL, rootUrl, config.getApiKey(),
        config.getCallback());
  }

  @Override
  public String getAccessTokenEndpoint() {
    return String.format("%s/oauth/token", rootUrl);
  }

  @Override
  public Verb getAccessTokenVerb() {
    return Verb.POST;
  }

  @Override
  public OAuthService createService(OAuthConfig config) {
    return new GitLabOAuthService(this, config);
  }

  @Override
  public AccessTokenExtractor getAccessTokenExtractor() {
    return new GitLabJsonTokenExtractor();
  }

  private static final class GitLabOAuthService implements OAuthService {
    private static final String VERSION = "2.0";

    private static final String GRANT_TYPE = "grant_type";
    private static final String GRANT_TYPE_VALUE = "authorization_code";

    private final DefaultApi20 api;
    private final OAuthConfig config;

    /**
     * Default constructor
     *
     * @param api    OAuth2.0 api information
     * @param config OAuth 2.0 configuration param object
     */
    public GitLabOAuthService(DefaultApi20 api, OAuthConfig config) {
      this.api = api;
      this.config = config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Token getAccessToken(Token requestToken, Verifier verifier) {
      OAuthRequest request =
          new OAuthRequest(api.getAccessTokenVerb(),
              api.getAccessTokenEndpoint());
      request.addBodyParameter(OAuthConstants.CLIENT_ID, config.getApiKey());
      request.addBodyParameter(OAuthConstants.CLIENT_SECRET,
          config.getApiSecret());
      request.addBodyParameter(OAuthConstants.CODE, verifier.getValue());
      request.addBodyParameter(OAuthConstants.REDIRECT_URI,
          config.getCallback());
      if (config.hasScope())
        request.addBodyParameter(OAuthConstants.SCOPE, config.getScope());
      request.addBodyParameter(GRANT_TYPE, GRANT_TYPE_VALUE);
      Response response = request.send();
      return api.getAccessTokenExtractor().extract(response.getBody());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Token getRequestToken() {
      throw new UnsupportedOperationException(
          "Unsupported operation, please use 'getAuthorizationUrl' and redirect your users there");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getVersion() {
      return VERSION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void signRequest(Token accessToken, OAuthRequest request) {
      request.addQuerystringParameter(OAuthConstants.ACCESS_TOKEN,
          accessToken.getToken());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAuthorizationUrl(Token requestToken) {
      return api.getAuthorizationUrl(config);
    }
  }

  private static final class GitLabJsonTokenExtractor implements
      AccessTokenExtractor {
    private Pattern accessTokenPattern = Pattern
        .compile("\"access_token\"\\s*:\\s*\"(\\S*?)\"");

    @Override
    public Token extract(String response) {
      Preconditions.checkEmptyString(response,
          "Cannot extract a token from a null or empty String");
      Matcher matcher = accessTokenPattern.matcher(response);
      if (matcher.find()) {
        return new Token(matcher.group(1), "", response);
      } else {
        throw new OAuthException(
            "Cannot extract an acces token. Response was: " + response);
      }
    }
  }
}
