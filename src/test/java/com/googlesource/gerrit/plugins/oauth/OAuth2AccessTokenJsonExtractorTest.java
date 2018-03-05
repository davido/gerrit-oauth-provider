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

import static org.junit.Assert.assertEquals;
import static org.scribe.model.OAuthConstants.ACCESS_TOKEN;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.scribe.exceptions.OAuthException;
import org.scribe.extractors.AccessTokenExtractor;
import org.scribe.model.Token;

public class OAuth2AccessTokenJsonExtractorTest {
  private static final AccessTokenExtractor extractor = OAuth2AccessTokenJsonExtractor.instance();
  private static final String TOKEN = "I0122HHJKLEM21F3WLPYHDKGKZULAUO4SGMV3ABKFTDT3T3X";
  private static final String RESPONSE = "{\"" + ACCESS_TOKEN + "\":\"" + TOKEN + "\"}'";
  private static final String RESPONSE_NON_JSON = ACCESS_TOKEN + "=" + TOKEN;
  private static final String RESPONSE_WITH_BLANKS =
      "{ \"" + ACCESS_TOKEN + "\" : \"" + TOKEN + "\"}'";
  private static final String MESSAGE = "Cannot extract a token from a null or empty String";

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void parseResponse() throws Exception {
    Token token = extractor.extract(RESPONSE);
    assertEquals(token.getToken(), TOKEN);
  }

  @Test
  public void parseResponseWithBlanks() throws Exception {
    Token token = extractor.extract(RESPONSE_WITH_BLANKS);
    assertEquals(token.getToken(), TOKEN);
  }

  @Test
  public void failParseNonJsonResponse() throws Exception {
    exception.expect(OAuthException.class);
    exception.expectMessage("Cannot extract an access token. Response was: " + RESPONSE_NON_JSON);
    extractor.extract(RESPONSE_NON_JSON);
  }

  @Test
  public void shouldThrowExceptionIfForNullParameter() throws Exception {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage(MESSAGE);
    extractor.extract(null);
  }

  @Test
  public void shouldThrowExceptionIfForEmptyString() throws Exception {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage(MESSAGE);
    extractor.extract("");
  }
}
