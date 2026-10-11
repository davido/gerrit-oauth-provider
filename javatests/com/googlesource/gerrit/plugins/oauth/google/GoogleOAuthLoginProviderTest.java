// Copyright (C) 2026 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.google;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidator;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GoogleOAuthLoginProviderTest {
  @Mock private OAuthTokenValidator validator;

  @Test
  public void login_nullSecret_throwsIOException() {
    assertThrows(
        IOException.class, () -> new GoogleOAuthLoginProvider(validator).login("jane", null));
  }

  @Test
  public void login_validToken_returnsUserInfo() throws Exception {
    when(validator.validate("token")).thenReturn(userInfo("jane"));

    OAuthUserInfo info = new GoogleOAuthLoginProvider(validator).login("jane", "token");

    assertThat(info.getExternalId()).isEqualTo("google-oauth:12345");
    assertThat(info.getEmailAddress()).isEqualTo("jane@example.com");
  }

  @Test
  public void login_nullClientUsername_stillReturnsUserInfo() throws Exception {
    when(validator.validate("token")).thenReturn(userInfo("jane"));

    OAuthUserInfo info = new GoogleOAuthLoginProvider(validator).login(null, "token");

    assertThat(info.getExternalId()).isEqualTo("google-oauth:12345");
  }

  @Test
  public void login_usernameMismatch_throwsIOException() throws Exception {
    when(validator.validate("token")).thenReturn(userInfo("bob"));

    assertThrows(
        IOException.class, () -> new GoogleOAuthLoginProvider(validator).login("jane", "token"));
  }

  private static OAuthUserInfo userInfo(String username) {
    return new OAuthUserInfo("google-oauth:12345", username, "jane@example.com", null, null);
  }
}
