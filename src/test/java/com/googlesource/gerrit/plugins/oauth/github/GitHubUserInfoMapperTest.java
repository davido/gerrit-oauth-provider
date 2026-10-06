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
import static org.junit.Assert.assertThrows;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import java.io.IOException;
import org.junit.Test;

public class GitHubUserInfoMapperTest {
  private static final String EXT_ID_SCHEME =
      OAuthServiceProviderExternalIdScheme.create(GitHubOAuthService.PROVIDER_NAME);

  @Test
  public void mapsIdLoginEmailName() throws Exception {
    OAuthUserInfo info =
        new GitHubUserInfoMapper(EXT_ID_SCHEME, false)
            .map(
                object(
                    "{\"id\":12345,\"login\":\"octocat\",\"email\":\"octo@github.com\",\"name\":\"The"
                        + " Octocat\"}"));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_SCHEME + ":12345");
    assertThat(info.getUserName()).isEqualTo("octocat");
    assertThat(info.getEmailAddress()).isEqualTo("octo@github.com");
    assertThat(info.getDisplayName()).isEqualTo("The Octocat");
    assertThat(info.getClaimedIdentity()).isNull();
  }

  @Test
  public void fixLegacyUserIdSetsClaimedIdentity() throws Exception {
    OAuthUserInfo info =
        new GitHubUserInfoMapper(EXT_ID_SCHEME, true).map(object("{\"id\":7,\"login\":\"x\"}"));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_SCHEME + ":7");
    assertThat(info.getClaimedIdentity()).isEqualTo("7");
  }

  @Test
  public void missingIdThrows() {
    assertThrows(
        IOException.class,
        () -> new GitHubUserInfoMapper(EXT_ID_SCHEME, false).map(object("{\"login\":\"x\"}")));
  }

  private static JsonObject object(String json) {
    return JsonParser.parseString(json).getAsJsonObject();
  }
}
