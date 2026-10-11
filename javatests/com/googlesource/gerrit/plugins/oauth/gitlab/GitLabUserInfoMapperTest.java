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

package com.googlesource.gerrit.plugins.oauth.gitlab;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import org.junit.Test;

public class GitLabUserInfoMapperTest {
  private static final String EXT_ID_SCHEME =
      OAuthServiceProviderExternalIdScheme.create(GitLabOAuthService.PROVIDER_NAME);

  private final GitLabUserInfoMapper mapper = new GitLabUserInfoMapper(EXT_ID_SCHEME);

  @Test
  public void mapsIdUsernameEmailName() throws Exception {
    OAuthUserInfo info =
        mapper.map(
            object(
                "{\"id\":42,\"username\":\"tanuki\",\"email\":\"t@gitlab.com\",\"name\":\"The"
                    + " Tanuki\"}"));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_SCHEME + ":42");
    assertThat(info.getUserName()).isEqualTo("tanuki");
    assertThat(info.getEmailAddress()).isEqualTo("t@gitlab.com");
    assertThat(info.getDisplayName()).isEqualTo("The Tanuki");
  }

  @Test
  public void missingOptionalFieldsMapToNull() throws Exception {
    OAuthUserInfo info = mapper.map(object("{\"id\":7}"));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_SCHEME + ":7");
    assertThat(info.getUserName()).isNull();
    assertThat(info.getEmailAddress()).isNull();
    assertThat(info.getDisplayName()).isNull();
  }

  private static JsonObject object(String json) {
    return JsonParser.parseString(json).getAsJsonObject();
  }
}
