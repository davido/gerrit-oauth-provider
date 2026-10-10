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

import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.asString;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.firstPresent;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;

/** Maps validated Keycloak JWT claims to {@link OAuthUserInfo}; shared by browser and Git paths. */
final class KeycloakUserInfoMapper {
  private final boolean usePreferredUsername;
  private final String extIdScheme;
  private final boolean linkToExistingGerrit;

  KeycloakUserInfoMapper(
      boolean usePreferredUsername, String extIdScheme, boolean linkToExistingGerrit) {
    this.usePreferredUsername = usePreferredUsername;
    this.extIdScheme = extIdScheme;
    this.linkToExistingGerrit = linkToExistingGerrit;
  }

  /** Maps to Gerrit user info with no claimed identity. Used by the Git-over-HTTP path. */
  OAuthUserInfo map(JsonObject claimObject) throws IOException {
    return build(claimObject, /* claimedIdentity= */ null);
  }

  /**
   * Browser mapping: when link-to-existing-gerrit-accounts is set, adds a {@code gerrit:<username>}
   * claimed identity so a first login links to an existing account (matching the Discovery and SAP
   * IAS providers). Fails closed if linking is requested but no preferred_username is present, so
   * linking is never silently skipped. Claimed identity is only read by the browser {@code
   * OAuthSession}; the Git path uses {@link #map} and is unaffected by the flag.
   */
  OAuthUserInfo mapForBrowser(JsonObject claimObject) throws IOException {
    String claimedIdentity = null;
    if (linkToExistingGerrit) {
      String usernameStr = asString(firstPresent(claimObject, "preferred_username"));
      if (usernameStr == null || usernameStr.isBlank()) {
        throw new IOException(
            "link-to-existing-gerrit-accounts is set but the response has no"
                + " preferred_username to build the gerrit:<username> claimed identity");
      }
      claimedIdentity = "gerrit:" + usernameStr;
    }
    return build(claimObject, claimedIdentity);
  }

  private OAuthUserInfo build(JsonObject claimObject, String claimedIdentity) throws IOException {
    JsonElement usernameElement = claimObject.get("preferred_username");
    JsonElement emailElement = claimObject.get("email");
    JsonElement nameElement = claimObject.get("name");
    if (isNull(usernameElement)) {
      throw new IOException("Response doesn't contain preferred_username field");
    }
    if (isNull(emailElement)) {
      throw new IOException("Response doesn't contain email field");
    }
    if (isNull(nameElement)) {
      throw new IOException("Response doesn't contain name field");
    }
    String usernameAsString = usernameElement.getAsString();
    String username = usePreferredUsername ? usernameAsString : null;
    String externalId = extIdScheme + ":" + usernameAsString;
    return new OAuthUserInfo(
        externalId,
        username,
        emailElement.getAsString(),
        nameElement.getAsString(),
        claimedIdentity);
  }
}
