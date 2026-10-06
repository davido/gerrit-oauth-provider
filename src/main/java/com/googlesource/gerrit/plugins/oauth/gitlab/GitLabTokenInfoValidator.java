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

import static com.google.gerrit.json.OutputFormat.JSON;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.common.collect.ImmutableSet;
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
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCache;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidator;
import com.googlesource.gerrit.plugins.oauth.client.OAuthHttpTransport;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.servlet.http.HttpServletResponse;

/**
 * Validates an opaque GitLab {@code access_token} for Git-over-HTTP. Confirms the token was issued
 * for a trusted OAuth application (the browser {@code client-id} plus any {@code trusted-audience}
 * entries) and carries the required scope(s) via {@code /oauth/token/info}, binds its {@code
 * resource_owner_id} to {@code /api/v4/user}, and caches successes.
 */
@Singleton
class GitLabTokenInfoValidator implements OAuthTokenValidator {
  private static final String DEFAULT_REQUIRED_SCOPE = "read_user";

  private final OAuthTokenValidationCache validationCache;
  private final String tokenInfoEndpoint;
  private final String userEndpoint;
  private final ImmutableSet<String> trustedApplicationUids;
  private final ImmutableSet<String> requiredScopes;
  private final GitLabUserInfoMapper userInfoMapper;

  @Inject
  GitLabTokenInfoValidator(
      OAuthPluginConfigFactory cfgFactory, OAuthTokenValidationCache validationCache) {
    this.validationCache = validationCache;
    PluginConfig cfg = cfgFactory.create(GitLabOAuthService.PROVIDER_NAME);
    String rootUrl = OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL));
    this.tokenInfoEndpoint = rootUrl + "/oauth/token/info";
    this.userEndpoint = rootUrl + "/api/v4/user";
    String clientId = cfg.getString(OAuthConfigKeys.CLIENT_ID);
    if (clientId == null) {
      throw new ProvisionException(
          "GitLab Git-over-HTTP requires client-id to verify the token's application.");
    }
    ImmutableSet.Builder<String> uids = ImmutableSet.builder();
    uids.add(clientId);
    for (String audience : cfg.getStringList(OAuthConfigKeys.TRUSTED_AUDIENCE)) {
      if (audience != null && !audience.isBlank()) {
        uids.add(audience);
      }
    }
    this.trustedApplicationUids = uids.build();
    String[] configured = cfg.getStringList(OAuthConfigKeys.REQUIRED_SCOPE);
    this.requiredScopes =
        configured.length == 0
            ? ImmutableSet.of(DEFAULT_REQUIRED_SCOPE)
            : ImmutableSet.copyOf(configured);
    this.userInfoMapper =
        new GitLabUserInfoMapper(
            OAuthServiceProviderExternalIdScheme.create(GitLabOAuthService.PROVIDER_NAME));
  }

  @Override
  public OAuthUserInfo validate(String bearerToken) throws IOException {
    Optional<OAuthUserInfo> cached = validationCache.get(bearerToken);
    if (cached.isPresent()) {
      return cached.get();
    }
    JsonObject info = callTokenInfo(bearerToken);
    verifyApplication(info);
    verifyScopes(info);
    String resourceOwnerId = requireResourceOwnerId(info);

    JsonObject user = callUser(bearerToken);
    verifySubjectBinding(resourceOwnerId, user);
    verifyAccountActive(user);
    OAuthUserInfo userInfo = userInfoMapper.map(user);

    extractExpiresAtMillis(info).ifPresent(exp -> validationCache.put(bearerToken, userInfo, exp));
    return userInfo;
  }

  /** Calls {@code /oauth/token/info}. Package-private so tests can supply a canned response. */
  JsonObject callTokenInfo(String bearerToken) throws IOException {
    return getJsonObject(tokenInfoEndpoint, bearerToken, "token/info");
  }

  /** Calls {@code /api/v4/user}. Package-private so tests can supply a canned response. */
  JsonObject callUser(String bearerToken) throws IOException {
    return getJsonObject(userEndpoint, bearerToken, "user");
  }

  private static JsonObject getJsonObject(String url, String bearerToken, String what)
      throws IOException {
    OAuthHttpTransport.Response response =
        OAuthHttpTransport.request(
            "GET", url, Map.of("Authorization", "Bearer " + bearerToken), null);
    if (response.code == HttpServletResponse.SC_UNAUTHORIZED) {
      throw new IOException("GitLab rejected the token (HTTP 401) at " + what);
    }
    if (response.code != HttpServletResponse.SC_OK) {
      throw new IOException("GitLab " + what + " request failed: HTTP " + response.code);
    }
    JsonObject parsed;
    try {
      parsed = JSON.newGson().fromJson(response.body, JsonObject.class);
    } catch (JsonSyntaxException e) {
      throw new IOException("GitLab " + what + " response is not valid JSON", e);
    }
    if (parsed == null) {
      throw new IOException("GitLab " + what + " response is empty");
    }
    return parsed;
  }

  private void verifyApplication(JsonObject info) throws IOException {
    JsonElement app = info.get("application");
    JsonElement uid = app != null && app.isJsonObject() ? app.getAsJsonObject().get("uid") : null;
    if (uid == null
        || !uid.isJsonPrimitive()
        || !trustedApplicationUids.contains(uid.getAsString())) {
      throw new IOException("Token belongs to a different GitLab application");
    }
  }

  private void verifyScopes(JsonObject info) throws IOException {
    Set<String> tokenScopes = new HashSet<>();
    JsonElement scope = info.get("scope");
    if (scope != null && scope.isJsonArray()) {
      for (JsonElement e : scope.getAsJsonArray()) {
        if (e.isJsonPrimitive()) {
          tokenScopes.add(e.getAsString());
        }
      }
    }
    for (String required : requiredScopes) {
      if (!tokenScopes.contains(required)) {
        throw new IOException("Token is missing the required scope: " + required);
      }
    }
  }

  private static String requireResourceOwnerId(JsonObject info) throws IOException {
    JsonElement id = info.get("resource_owner_id");
    if (isNull(id)) {
      throw new IOException("GitLab token/info response is missing resource_owner_id");
    }
    return id.getAsString();
  }

  private static void verifySubjectBinding(String resourceOwnerId, JsonObject user)
      throws IOException {
    JsonElement id = user.get("id");
    if (isNull(id) || !resourceOwnerId.equals(id.getAsString())) {
      throw new IOException(
          "Subject mismatch: token/info resource_owner_id does not match the /api/v4/user id");
    }
  }

  /** Rejects a valid token whose account is blocked, deactivated, banned, or ldap_blocked. */
  private static void verifyAccountActive(JsonObject user) throws IOException {
    JsonElement state = user.get("state");
    if (isNull(state) || !"active".equals(state.getAsString())) {
      throw new IOException(
          "GitLab account is not active (state="
              + (isNull(state) ? "missing" : state.getAsString())
              + ")");
    }
  }

  private static Optional<Long> extractExpiresAtMillis(JsonObject info) {
    // token/info returns expires_in (seconds from now); a non-expiring token omits it, so don't
    // cache -- there is no upper bound on validity.
    JsonElement expiresIn = info.get("expires_in");
    if (expiresIn != null && expiresIn.isJsonPrimitive()) {
      try {
        return Optional.of(
            System.currentTimeMillis() + Long.parseLong(expiresIn.getAsString()) * 1000L);
      } catch (NumberFormatException ignored) {
        // fall through
      }
    }
    return Optional.empty();
  }
}
