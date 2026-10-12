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

package com.googlesource.gerrit.plugins.oauth.base;

import static com.google.gerrit.json.OutputFormat.JSON;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.jwtPayloadJson;

import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.oauth.client.OAuthClient;
import java.io.IOException;

/**
 * Base for OpenID Connect providers that read the user information from the {@code id_token}
 * carried in the token response, rather than from a separately fetched resource.
 *
 * <p>Subclasses supply only the mapping of the decoded claims ({@link #parseClaims(JsonObject)}),
 * the service name, and construct the {@link OAuthClient}. Extracting and decoding the {@code
 * id_token} JWT is handled here; token exchange, the authorization redirect and the version live in
 * {@link AbstractOAuthService}.
 */
public abstract class StandardIdTokenOAuthService extends AbstractOAuthService {

  protected StandardIdTokenOAuthService(String name) {
    super(name);
  }

  /** Maps the decoded {@code id_token} claims to Gerrit's user information. */
  protected abstract OAuthUserInfo parseClaims(JsonObject claims) throws IOException;

  /**
   * Decodes the {@code id_token} claims. The default base64-decodes the payload without verifying
   * the signature; providers can override to verify against the IdP's JWKS.
   */
  protected JsonObject decodeIdToken(String idToken) throws IOException {
    String jwt = jwtPayloadJson(idToken);
    JsonElement claimJson = JSON.newGson().fromJson(jwt, JsonElement.class);
    return claimJson.getAsJsonObject();
  }

  @Override
  public final OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    JsonElement tokenJson = JSON.newGson().fromJson(token.getRaw(), JsonElement.class);
    JsonObject tokenObject = tokenJson.getAsJsonObject();
    JsonElement idToken = tokenObject.get("id_token");
    JsonObject claimObject = decodeIdToken(idToken.getAsString());
    if (log.isDebugEnabled()) {
      log.debug("Claim object: {}", claimObject);
    }
    return parseClaims(claimObject);
  }
}
