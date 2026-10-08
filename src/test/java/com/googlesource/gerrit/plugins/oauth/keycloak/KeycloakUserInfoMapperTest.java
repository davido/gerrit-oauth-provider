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

package com.googlesource.gerrit.plugins.oauth.keycloak;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import java.io.IOException;
import org.junit.Test;

public class KeycloakUserInfoMapperTest {
  private static final String EXT_ID_SCHEME =
      OAuthServiceProviderExternalIdScheme.create(KeycloakOAuthService.PROVIDER_NAME);

  @Test
  public void map_ignoresLinkToExistingFlag_noClaimedIdentity() throws Exception {
    // even with the flag on, map() (the Git path) never sets a claimed identity; only
    // mapForBrowser() does. this is what makes the validator's hard-coded false safe.
    OAuthUserInfo info =
        linkingMapper()
            .map(
                object(
                    "{\"preferred_username\":\"alice\",\"email\":\"alice@example.com\","
                        + "\"name\":\"Alice\"}"));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_SCHEME + ":alice");
    assertThat(info.getUserName()).isEqualTo("alice");
    assertThat(info.getClaimedIdentity()).isNull();
  }

  @Test
  public void mapForBrowser_linkToExisting_setsClaimedIdentity() throws Exception {
    OAuthUserInfo info =
        linkingMapper()
            .mapForBrowser(
                object(
                    "{\"preferred_username\":\"alice\",\"email\":\"alice@example.com\","
                        + "\"name\":\"Alice\"}"));

    assertThat(info.getClaimedIdentity()).isEqualTo("gerrit:alice");
  }

  @Test
  public void mapForBrowser_linkToExisting_nonStringUsername_failsClosed() throws Exception {
    // a non-primitive preferred_username must fail closed with an IOException, not an unchecked
    // one leaking out of getAsString().
    assertThrows(
        IOException.class,
        () -> linkingMapper().mapForBrowser(object("{\"preferred_username\":{\"nested\":\"x\"}}")));
  }

  private static KeycloakUserInfoMapper linkingMapper() {
    return new KeycloakUserInfoMapper(
        /* usePreferredUsername= */ true, EXT_ID_SCHEME, /* linkToExistingGerrit= */ true);
  }

  private static JsonObject object(String json) {
    return JsonParser.parseString(json).getAsJsonObject();
  }
}
