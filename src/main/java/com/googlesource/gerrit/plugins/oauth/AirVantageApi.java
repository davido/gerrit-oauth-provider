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

import static java.lang.String.format;

import org.scribe.builder.api.DefaultApi20;
import org.scribe.extractors.AccessTokenExtractor;
import org.scribe.extractors.JsonTokenExtractor;
import org.scribe.model.OAuthConfig;
import org.scribe.model.Verb;
import org.scribe.oauth.OAuthService;

public class AirVantageApi extends DefaultApi20 {

  private static final String AUTHORIZE_URL =
      "https://eu.airvantage.net/api/oauth/authorize?client_id=%s&response_type=code";
  private static final String ACCESS_TOKEN_ENDPOINT = "https://eu.airvantage.net/api/oauth/token";

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
  public AccessTokenExtractor getAccessTokenExtractor() {
    return new JsonTokenExtractor();
  }

  @Override
  public OAuthService createService(OAuthConfig config) {
    return new OAuth20ServiceImpl(this, config);
  }
}
