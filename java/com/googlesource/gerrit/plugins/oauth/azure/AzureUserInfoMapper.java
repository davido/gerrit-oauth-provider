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

package com.googlesource.gerrit.plugins.oauth.azure;

import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.asString;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;

/**
 * Maps a Microsoft Graph {@code /me} response to {@link OAuthUserInfo}; shared by the browser and
 * Git-over-HTTP paths so both emit the same {@code azure-oauth:<me.id>} external id.
 */
final class AzureUserInfoMapper {
  private final String extIdScheme;
  private final String extIdDeprecatedScheme;
  private final boolean useEmailAsUsername;
  private final boolean linkOffice365Id;

  AzureUserInfoMapper(
      String extIdScheme,
      String extIdDeprecatedScheme,
      boolean useEmailAsUsername,
      boolean linkOffice365Id) {
    this.extIdScheme = extIdScheme;
    this.extIdDeprecatedScheme = extIdDeprecatedScheme;
    this.useEmailAsUsername = useEmailAsUsername;
    this.linkOffice365Id = linkOffice365Id;
  }

  /** Git-over-HTTP mapping: no claimed identity (claimed identity is browser-only). */
  OAuthUserInfo map(JsonObject me) throws IOException {
    return build(me, /* linkOffice365Id= */ false);
  }

  /**
   * Browser mapping: adds the deprecated {@code office365-oauth:<me.id>} claimed identity when
   * link-to-existing-office365-accounts is set, so a first login links a pre-existing Office365
   * account.
   */
  OAuthUserInfo mapForBrowser(JsonObject me) throws IOException {
    return build(me, linkOffice365Id);
  }

  private OAuthUserInfo build(JsonObject me, boolean linkOffice365Id) throws IOException {
    JsonElement id = me.get("id");
    if (isNull(id)) {
      throw new IOException("Response doesn't contain id field");
    }
    String idStr = id.getAsString();
    JsonElement email = me.get("mail");
    JsonElement name = me.get("displayName");
    String login =
        (useEmailAsUsername && !isNull(email)) ? email.getAsString().split("@")[0] : null;
    return new OAuthUserInfo(
        extIdScheme + ":" + idStr,
        login,
        asString(email),
        asString(name),
        linkOffice365Id ? extIdDeprecatedScheme + ":" + idStr : null);
  }
}
