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

import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.asString;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;

/** Maps GitLab's {@code /api/v4/user} response to {@link OAuthUserInfo}. */
final class GitLabUserInfoMapper {
  private final String extIdScheme;

  GitLabUserInfoMapper(String extIdScheme) {
    this.extIdScheme = extIdScheme;
  }

  OAuthUserInfo map(JsonObject user) throws IOException {
    if (isNull(user)) {
      throw new IOException("Response doesn't contain user object");
    }
    JsonElement id = user.get("id");
    JsonElement username = user.get("username");
    JsonElement email = user.get("email");
    JsonElement name = user.get("name");
    return new OAuthUserInfo(
        extIdScheme + ":" + id.getAsString(),
        asString(username),
        asString(email),
        asString(name),
        null);
  }
}
