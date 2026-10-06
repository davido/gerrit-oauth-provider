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

import static com.google.gerrit.json.OutputFormat.JSON;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.googlesource.gerrit.plugins.oauth.client.OAuthHttpTransport;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletResponse;

/**
 * Introspects a GitHub {@code access_token} at the check-token endpoint ({@code POST
 * /applications/{client_id}/token}) and maps the response to Gerrit user info. Shared by the
 * browser flow ({@link GitHubOAuthService}) and the Git-over-HTTP validator ({@link
 * GitHubCheckTokenValidator}, which adds caching).
 */
@Singleton
class GitHubCheckTokenClient {
  private final String clientId;
  private final String checkTokenEndpoint;
  private final String checkTokenAuthHeader;
  private final GitHubUserInfoMapper userInfoMapper;

  /** The mapped user plus the token's own expiry, for the caller to cache. */
  static final class Validated {
    final OAuthUserInfo userInfo;
    final Optional<Long> expiresAtMillis;

    Validated(OAuthUserInfo userInfo, Optional<Long> expiresAtMillis) {
      this.userInfo = userInfo;
      this.expiresAtMillis = expiresAtMillis;
    }
  }

  @Inject
  GitHubCheckTokenClient(OAuthPluginConfigFactory cfgFactory) {
    PluginConfig cfg = cfgFactory.create(GitHubOAuthService.PROVIDER_NAME);
    boolean fixLegacyUserId = cfg.getBoolean(OAuthConfigKeys.FIX_LEGACY_USER_ID, false);
    String rootUrl =
        OAuthUrls.trimTrailingSlashes(
            cfg.getString(OAuthConfigKeys.ROOT_URL, GitHubOAuthService.GITHUB_ROOT_URL));
    this.clientId = cfg.getString(OAuthConfigKeys.CLIENT_ID);
    String clientSecret = cfg.getString(OAuthConfigKeys.CLIENT_SECRET);
    if (clientId == null || clientSecret == null) {
      throw new ProvisionException(
          "GitHub token validation requires both client-id and client-secret to introspect tokens"
              + " at the check-token endpoint.");
    }
    this.checkTokenEndpoint = new GitHub2Api(rootUrl).getApplicationsTokenEndpoint(clientId);
    this.checkTokenAuthHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    userInfoMapper =
        new GitHubUserInfoMapper(
            OAuthServiceProviderExternalIdScheme.create(GitHubOAuthService.PROVIDER_NAME),
            fixLegacyUserId);
  }

  Validated validate(String bearerToken) throws IOException {
    JsonObject response = checkToken(bearerToken);
    verifyAppClientId(response);
    return new Validated(parseUserClaims(response), extractExpiresAtMillis(response));
  }

  /** Package-private so tests can supply a canned response without a network call. */
  JsonObject checkToken(String bearerToken) throws IOException {
    JsonObject payload = new JsonObject();
    payload.addProperty("access_token", bearerToken);
    OAuthHttpTransport.Response response =
        OAuthHttpTransport.request(
            "POST",
            checkTokenEndpoint,
            Map.of(
                "Authorization", checkTokenAuthHeader,
                "Accept", "application/vnd.github+json",
                "Content-Type", "application/json"),
            payload.toString());
    if (response.code == HttpServletResponse.SC_NOT_FOUND) {
      throw new IOException("Token does not belong to this Gerrit's GitHub OAuth app");
    }
    if (response.code == HttpServletResponse.SC_UNAUTHORIZED) {
      throw new IOException("GitHub rejected client credentials (HTTP 401)");
    }
    if (response.code != HttpServletResponse.SC_OK) {
      throw new IOException("GitHub token validation failed: HTTP " + response.code);
    }
    JsonObject parsed;
    try {
      parsed = JSON.newGson().fromJson(response.body, JsonObject.class);
    } catch (JsonSyntaxException e) {
      throw new IOException("GitHub check-token response is not valid JSON", e);
    }
    if (parsed == null) {
      throw new IOException("GitHub check-token response is empty");
    }
    return parsed;
  }

  private void verifyAppClientId(JsonObject response) throws IOException {
    JsonElement app = response.get("app");
    if (app == null || !app.isJsonObject()) {
      throw new IOException("GitHub check-token response is missing the app object");
    }
    JsonElement actualClientId = app.getAsJsonObject().get("client_id");
    if (actualClientId == null
        || !actualClientId.isJsonPrimitive()
        || !clientId.equals(actualClientId.getAsString())) {
      throw new IOException("Token belongs to a different GitHub OAuth app");
    }
  }

  private OAuthUserInfo parseUserClaims(JsonObject response) throws IOException {
    JsonElement userElement = response.get("user");
    if (userElement == null || !userElement.isJsonObject()) {
      throw new IOException("GitHub check-token response is missing the user object");
    }
    return userInfoMapper.map(userElement.getAsJsonObject());
  }

  private static Optional<Long> extractExpiresAtMillis(JsonObject response) {
    JsonElement expiresAt = response.get("expires_at");
    if (expiresAt == null || expiresAt.isJsonNull() || !expiresAt.isJsonPrimitive()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(expiresAt.getAsString()).toEpochMilli());
    } catch (DateTimeParseException ignored) {
      return Optional.empty();
    }
  }
}
