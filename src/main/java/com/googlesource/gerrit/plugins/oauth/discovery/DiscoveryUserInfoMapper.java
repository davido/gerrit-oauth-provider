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

package com.googlesource.gerrit.plugins.oauth.discovery;

import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.asString;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.firstPresent;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;

/** Maps standard OIDC claims to {@link OAuthUserInfo}; shared by the browser and Git paths. */
final class DiscoveryUserInfoMapper {
  private final String extIdScheme;
  private final boolean linkToExistingGerrit;

  DiscoveryUserInfoMapper(String extIdScheme, boolean linkToExistingGerrit) {
    this.extIdScheme = extIdScheme;
    this.linkToExistingGerrit = linkToExistingGerrit;
  }

  /** Maps to Gerrit user info with no claimed identity. Used by the Git-over-HTTP path. */
  OAuthUserInfo map(JsonObject claims) throws IOException {
    return build(claims, /* claimedIdentity= */ null);
  }

  /**
   * Browser mapping: when link-to-existing-gerrit-accounts is set, adds a {@code gerrit:<username>}
   * claimed identity so a first login links to an existing account (matching the Authentik/Cognito
   * wrappers). Fails closed if linking is requested but no username is present, so linking is never
   * silently skipped. Claimed identity is only read by the browser {@code OAuthSession}; the Git
   * path uses {@link #map} and is unaffected by the flag.
   */
  OAuthUserInfo mapForBrowser(JsonObject claims) throws IOException {
    String claimedIdentity = null;
    if (linkToExistingGerrit) {
      String usernameStr = asString(firstPresent(claims, "preferred_username", "username"));
      if (usernameStr == null || usernameStr.isBlank()) {
        throw new IOException(
            "link-to-existing-gerrit-accounts is set but the response has no"
                + " preferred_username/username to build the gerrit:<username> claimed identity");
      }
      claimedIdentity = "gerrit:" + usernameStr;
    }
    return build(claims, claimedIdentity);
  }

  private OAuthUserInfo build(JsonObject claims, String claimedIdentity) throws IOException {
    JsonElement sub = claims.get("sub");
    if (sub == null || sub.isJsonNull()) {
      throw new IOException("Response doesn't contain sub field");
    }
    JsonElement username = firstPresent(claims, "preferred_username", "username");
    JsonElement email = claims.get("email");
    JsonElement name = firstPresent(claims, "name", "display_name");
    return new OAuthUserInfo(
        extIdScheme + ":" + sub.getAsString(),
        asString(username),
        asString(email),
        asString(name),
        claimedIdentity);
  }
}
