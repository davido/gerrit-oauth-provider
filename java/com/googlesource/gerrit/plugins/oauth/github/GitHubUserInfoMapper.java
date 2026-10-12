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

import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.asString;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;

/**
 * Maps a GitHub user object to {@link OAuthUserInfo}. The same object shape is returned by {@code
 * /user} (browser flow) and by the check-token {@code user} field (Git-over-HTTP), so both paths
 * share this mapper.
 */
final class GitHubUserInfoMapper {
  private final String extIdScheme;
  private final boolean fixLegacyUserId;

  GitHubUserInfoMapper(String extIdScheme, boolean fixLegacyUserId) {
    this.extIdScheme = extIdScheme;
    this.fixLegacyUserId = fixLegacyUserId;
  }

  OAuthUserInfo map(JsonObject user) throws IOException {
    JsonElement id = user.get("id");
    if (isNull(id)) {
      throw new IOException("GitHub user object is missing the id field");
    }
    return new OAuthUserInfo(
        extIdScheme + ":" + id.getAsString(),
        asString(user.get("login")),
        asString(user.get("email")),
        asString(user.get("name")),
        fixLegacyUserId ? id.getAsString() : null);
  }
}
