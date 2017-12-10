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
import org.scribe.extractors.AccessTokenExtractor;
import org.scribe.extractors.JsonTokenExtractor;
import org.scribe.model.OAuthConfig;
import org.scribe.model.Verb;
import org.scribe.oauth.OAuthService;
import org.scribe.utils.OAuthEncoder;

public class DexApi extends DefaultApi20 {

  private static final String AUTHORIZE_URL =
      "%s/dex/auth?client_id=%s&response_type=code&redirect_uri=%s&scope=%s";

  private final String rootUrl;

  public DexApi(String rootUrl) {
    this.rootUrl = rootUrl;
  }

  @Override
  public String getAuthorizationUrl(OAuthConfig config) {
    return String.format(
        AUTHORIZE_URL,
        rootUrl,
        config.getApiKey(),
        OAuthEncoder.encode(config.getCallback()),
        config.getScope().replaceAll(" ", "+"));
  }

  @Override
  public String getAccessTokenEndpoint() {
    return String.format("%s/dex/token", rootUrl);
  }

  @Override
  public Verb getAccessTokenVerb() {
    return Verb.POST;
  }

  @Override
  public OAuthService createService(OAuthConfig config) {
    return new OAuth20ServiceImpl(this, config);
  }

  @Override
  public AccessTokenExtractor getAccessTokenExtractor() {
    return new JsonTokenExtractor();
  }
}
