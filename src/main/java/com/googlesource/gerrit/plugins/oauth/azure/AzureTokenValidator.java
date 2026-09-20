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

import static com.google.gerrit.json.OutputFormat.JSON;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.firstString;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.jwtPayloadJson;

import com.google.common.base.Strings;
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
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletResponse;

/**
 * Validates an Azure (Entra ID) {@code access_token} presented on the Git-over-HTTP path.
 *
 * <p>Microsoft documents that Graph access tokens are opaque to clients and only Microsoft Graph
 * can validate them, so this is introspection-style (like Google/GitHub/GitLab), not local JWKS
 * validation. Layers:
 *
 * <ul>
 *   <li>{@code intro} -- {@code GET graph/v1.0/me} (with the same wildcard {@code Accept} header
 *       the browser flow uses) proves the token is authentic and unexpired and yields {@code
 *       me.id}, giving external id parity ({@code azure-oauth:<me.id>}) with the browser flow.
 *   <li>{@code app} -- Graph {@code /me} accepts any {@code User.Read} token, so the token's
 *       client-app claims are checked to defend against confused-deputy: at least one of {@code
 *       appid}/{@code azp} must be present, and every present one must equal {@code client-id}. The
 *       claims are read from the token payload without signature validation (which Graph tokens do
 *       not support); an opaque/unparseable token cannot prove this binding and is rejected.
 *   <li>{@code tid} -- for a fixed tenant GUID, the token's {@code tid} must equal the configured
 *       tenant (skipped for the multi-tenant aliases).
 *   <li>{@code cache} -- successes are cached, but only when the token carries a parseable {@code
 *       exp} (Graph {@code /me} returns no expiry); otherwise it is validated via Graph every time.
 * </ul>
 *
 * <p>The presented token must be minted by the Azure app registration Gerrit trusts (so {@code
 * appid}/{@code azp == client-id}); a separate helper app would need an Azure trusted-audience
 * equivalent, which is not implemented.
 */
@Singleton
class AzureTokenValidator implements OAuthTokenValidator {
  private static final String GRAPH_ME_URL = "https://graph.microsoft.com/v1.0/me";

  private final OAuthTokenValidationCache validationCache;
  private final String clientId;
  private final String tenant;
  private final AzureUserInfoMapper userInfoMapper;

  @Inject
  AzureTokenValidator(
      OAuthPluginConfigFactory cfgFactory, OAuthTokenValidationCache validationCache) {
    this.validationCache = validationCache;
    PluginConfig cfg = cfgFactory.create(AzureActiveDirectoryService.PROVIDER_NAME);
    String clientId = cfg.getString(OAuthConfigKeys.CLIENT_ID);
    if (Strings.isNullOrEmpty(clientId)) {
      // Fail fast at startup (like the browser fixed-tenant validator): a blank client-id can never
      // match a token's appid/azp, so every Git request would otherwise fail at validation time.
      throw new ProvisionException(
          "Azure Git-over-HTTP requires a non-blank client-id to bind the token's appid/azp");
    }
    this.clientId = clientId;
    this.tenant = cfg.getString(OAuthConfigKeys.TENANT, AzureActiveDirectoryService.DEFAULT_TENANT);
    boolean useEmailAsUsername = cfg.getBoolean(OAuthConfigKeys.USE_EMAIL_AS_USERNAME, false);
    // The Git path never sets a claimed identity (browser-only), so linkOffice365Id is false here.
    this.userInfoMapper =
        new AzureUserInfoMapper(
            OAuthServiceProviderExternalIdScheme.create(AzureActiveDirectoryService.PROVIDER_NAME),
            OAuthServiceProviderExternalIdScheme.create(
                AzureActiveDirectoryService.PROVIDER_DEPRECATED_NAME),
            useEmailAsUsername,
            /* linkOffice365Id= */ false);
  }

  @Override
  public OAuthUserInfo validate(String bearerToken) throws IOException {
    Optional<OAuthUserInfo> cached = validationCache.get(bearerToken);
    if (cached.isPresent()) {
      return cached.get();
    }

    Optional<JsonObject> claims = parseAccessTokenClaims(bearerToken);
    if (claims.isEmpty()) {
      // Opaque or unparseable token: the appid/azp binding cannot be proven, and accepting it after
      // Graph /me would let a token minted for another app through -- a confused-deputy hole.
      throw new IOException(
          "Azure Git-over-HTTP requires a parseable JWT access token to verify the client-app"
              + " binding; the presented token is opaque or unparseable");
    }
    JsonObject payload = claims.get();
    verifyAppBinding(payload);
    verifyTenant(payload);

    JsonObject me = callGraphMe(bearerToken);
    OAuthUserInfo userInfo = userInfoMapper.map(me);
    extractExpiresAtMillis(payload)
        .ifPresent(exp -> validationCache.put(bearerToken, userInfo, exp));
    return userInfo;
  }

  /**
   * Google-style client-app invariant: at least one of {@code appid}/{@code azp} must be present,
   * and every present one must equal {@code client-id} (an OR check would let {@code
   * appid=<gerrit>} plus {@code azp=<foreign>} through).
   */
  private void verifyAppBinding(JsonObject payload) throws IOException {
    String appid = firstString(payload, "appid");
    String azp = firstString(payload, "azp");
    if (appid == null && azp == null) {
      throw new IOException(
          "Azure access token has no appid/azp client-app claim to bind to the client-id");
    }
    if (appid != null && !appid.equals(clientId)) {
      throw new IOException("Azure access token appid does not match the configured client-id");
    }
    if (azp != null && !azp.equals(clientId)) {
      throw new IOException("Azure access token azp does not match the configured client-id");
    }
  }

  private void verifyTenant(JsonObject payload) throws IOException {
    if (AzureActiveDirectoryService.TENANTS_WITHOUT_VALIDATION.contains(tenant)) {
      // Multi-tenant alias: no single tenant to pin (mirrors the browser flow).
      return;
    }
    String tid = firstString(payload, "tid");
    if (tid == null || !tid.equals(tenant)) {
      throw new IOException("Azure access token tid does not match the configured tenant");
    }
  }

  /** Calls Graph {@code /me}. Package-private so tests can supply a canned response. */
  JsonObject callGraphMe(String bearerToken) throws IOException {
    OAuthHttpTransport.Response response =
        OAuthHttpTransport.request(
            "GET",
            GRAPH_ME_URL,
            Map.of("Authorization", "Bearer " + bearerToken, "Accept", "*/*"),
            null);
    if (response.code != HttpServletResponse.SC_OK) {
      throw new IOException("Microsoft Graph rejected token: HTTP " + response.code);
    }
    JsonObject parsed;
    try {
      parsed = JSON.newGson().fromJson(response.body, JsonObject.class);
    } catch (JsonSyntaxException e) {
      throw new IOException("Microsoft Graph /me response is not valid JSON", e);
    }
    if (parsed == null) {
      throw new IOException("Microsoft Graph /me response is empty");
    }
    return parsed;
  }

  /** Reads the JWT payload claims, or empty for an opaque/unparseable token. */
  private static Optional<JsonObject> parseAccessTokenClaims(String bearerToken) {
    try {
      return Optional.ofNullable(
          JSON.newGson().fromJson(jwtPayloadJson(bearerToken), JsonObject.class));
    } catch (RuntimeException | IOException e) {
      return Optional.empty();
    }
  }

  private static Optional<Long> extractExpiresAtMillis(JsonObject payload) {
    JsonElement exp = payload.get("exp");
    if (exp == null || !exp.isJsonPrimitive()) {
      return Optional.empty();
    }
    try {
      return Optional.of(exp.getAsLong() * 1000L);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
