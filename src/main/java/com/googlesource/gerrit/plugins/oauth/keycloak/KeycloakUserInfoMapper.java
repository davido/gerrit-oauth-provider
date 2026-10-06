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

import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;

/** Maps validated Keycloak JWT claims to {@link OAuthUserInfo}; shared by browser and Git paths. */
final class KeycloakUserInfoMapper {
  private final boolean usePreferredUsername;
  private final String extIdScheme;

  KeycloakUserInfoMapper(boolean usePreferredUsername, String extIdScheme) {
    this.usePreferredUsername = usePreferredUsername;
    this.extIdScheme = extIdScheme;
  }

  OAuthUserInfo map(JsonObject claimObject) throws IOException {
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
        externalId, username, emailElement.getAsString(), nameElement.getAsString(), null);
  }
}
