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

import static org.scribe.utils.OAuthEncoder.encode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.scribe.builder.api.DefaultApi20;
import org.scribe.exceptions.OAuthException;
import org.scribe.extractors.AccessTokenExtractor;
import org.scribe.model.OAuthConfig;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.oauth.OAuthService;
import org.scribe.utils.Preconditions;

// Source: https://github.com/FeedTheCoffers/scribe-java-extras
// License: Apache 2
// https://github.com/FeedTheCoffers/scribe-java-extras/blob/master/pom.xml
public class Google2Api extends DefaultApi20 {
  private static final String AUTHORIZE_URL =
      "https://accounts.google.com/o/oauth2/auth?response_type=code&client_id=%s&redirect_uri=%s&scope=%s";

  @Override
  public String getAccessTokenEndpoint() {
    return "https://accounts.google.com/o/oauth2/token";
  }

  @Override
  public String getAuthorizationUrl(OAuthConfig config) {
    Preconditions.checkValidUrl(
        config.getCallback(), "Must provide a valid url as callback. Google does not support OOB");
    Preconditions.checkEmptyString(
        config.getScope(), "Must provide a valid value as scope. Google does not support no scope");

    return String.format(
        AUTHORIZE_URL, config.getApiKey(), encode(config.getCallback()), encode(config.getScope()));
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
    return new GoogleJsonTokenExtractor();
  }

  private static final class GoogleJsonTokenExtractor implements AccessTokenExtractor {
    private Pattern accessTokenPattern = Pattern.compile("\"access_token\"\\s*:\\s*\"(\\S*?)\"");

    @Override
    public Token extract(String response) {
      Preconditions.checkEmptyString(
          response, "Cannot extract a token from a null or empty String");
      Matcher matcher = accessTokenPattern.matcher(response);
      if (matcher.find()) {
        return new Token(matcher.group(1), "", response);
      }

      throw new OAuthException("Cannot extract an acces token. Response was: " + response);
    }
  }
}
