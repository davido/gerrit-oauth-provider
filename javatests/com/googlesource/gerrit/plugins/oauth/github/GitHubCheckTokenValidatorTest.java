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

package com.googlesource.gerrit.plugins.oauth.github;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCache;
import com.googlesource.gerrit.plugins.oauth.github.GitHubCheckTokenClient.Validated;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GitHubCheckTokenValidatorTest {
  @Mock private GitHubCheckTokenClient client;
  @Mock private OAuthTokenValidationCache cache;

  @Test
  public void validate_cacheHit_returnsCachedWithoutCallingClient() throws Exception {
    OAuthUserInfo cached = userInfo();
    when(cache.get("token")).thenReturn(Optional.of(cached));

    assertThat(new GitHubCheckTokenValidator(client, cache).validate("token"))
        .isSameInstanceAs(cached);
    verify(client, never()).validate(anyString());
  }

  @Test
  public void validate_cacheMiss_validatesAndCaches() throws Exception {
    OAuthUserInfo info = userInfo();
    when(cache.get("token")).thenReturn(Optional.empty());
    when(client.validate("token")).thenReturn(new Validated(info, Optional.of(123456789L)));

    assertThat(new GitHubCheckTokenValidator(client, cache).validate("token"))
        .isSameInstanceAs(info);
    verify(cache).put("token", info, 123456789L);
  }

  @Test
  public void validate_cacheMissWithoutExpiry_notCached() throws Exception {
    OAuthUserInfo info = userInfo();
    when(cache.get("token")).thenReturn(Optional.empty());
    when(client.validate("token")).thenReturn(new Validated(info, Optional.empty()));

    assertThat(new GitHubCheckTokenValidator(client, cache).validate("token"))
        .isSameInstanceAs(info);
    verify(cache, never()).put(anyString(), any(OAuthUserInfo.class), anyLong());
  }

  private static OAuthUserInfo userInfo() {
    return new OAuthUserInfo(
        "github-oauth:12345", "octocat", "octo@github.com", "The Octocat", null);
  }
}
