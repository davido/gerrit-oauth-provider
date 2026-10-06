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

package com.googlesource.gerrit.plugins.oauth.google;

import static com.google.gerrit.json.OutputFormat.JSON;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.firstString;

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
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates an opaque Google {@code access_token} at the {@code tokeninfo} endpoint, checking
 * audience and verified email against the trusted audience set (client-id plus any configured
 * trusted-audience); caches successes.
 */
@Singleton
class GoogleTokenInfoValidator implements OAuthTokenValidator {
  private static final Logger log = LoggerFactory.getLogger(GoogleTokenInfoValidator.class);

  private final OAuthTokenValidationCache validationCache;
  private final String tokenInfoEndpoint;
  private final ImmutableSet<String> trustedAudiences;
  private final boolean useEmailAsUsername;
  private final boolean fixLegacyUserId;
  private final String extIdScheme;

  @Inject
  GoogleTokenInfoValidator(
      OAuthPluginConfigFactory cfgFactory, OAuthTokenValidationCache validationCache) {
    this.validationCache = validationCache;
    PluginConfig cfg = cfgFactory.create(GoogleOAuthService.PROVIDER_NAME);
    this.tokenInfoEndpoint = new Google2Api().getTokenInfoEndpoint();
    this.trustedAudiences =
        GoogleClientId.trustedAudiences(
            cfg.getString(OAuthConfigKeys.CLIENT_ID),
            cfg.getStringList(OAuthConfigKeys.TRUSTED_AUDIENCE));
    // tokeninfo carries no hd claim, so a domain restriction cannot be enforced here; fail fast.
    if (cfg.getStringList(OAuthConfigKeys.DOMAIN).length > 0) {
      throw new ProvisionException(
          "Google Git-over-HTTP cannot enforce the configured hosted-domain (domain) restriction:"
              + " Google's tokeninfo response for an access token does not include the hd claim."
              + " Remove the domain restriction, or set enable-git-over-http = false for Google.");
    }
    this.useEmailAsUsername = cfg.getBoolean(OAuthConfigKeys.USE_EMAIL_AS_USERNAME, false);
    this.fixLegacyUserId = cfg.getBoolean(OAuthConfigKeys.FIX_LEGACY_USER_ID, false);
    this.extIdScheme =
        OAuthServiceProviderExternalIdScheme.create(GoogleOAuthService.PROVIDER_NAME);
  }

  @Override
  public OAuthUserInfo validate(String bearerToken) throws IOException {
    Optional<OAuthUserInfo> cached = validationCache.get(bearerToken);
    if (cached.isPresent()) {
      return cached.get();
    }
    JsonObject response = callTokeninfo(bearerToken);
    verifyAudience(response);
    verifyEmailVerified(response);
    OAuthUserInfo userInfo = parseUserClaims(response);
    extractExpiresAtMillis(response)
        .ifPresent(exp -> validationCache.put(bearerToken, userInfo, exp));
    return userInfo;
  }

  /** Calls the tokeninfo endpoint. Package-private so tests can supply a canned response. */
  JsonObject callTokeninfo(String bearerToken) throws IOException {
    String url =
        tokenInfoEndpoint
            + "?access_token="
            + URLEncoder.encode(bearerToken, StandardCharsets.UTF_8);
    OAuthHttpTransport.Response response = OAuthHttpTransport.request("GET", url, Map.of(), null);
    if (response.code != HttpServletResponse.SC_OK) {
      // Google returns 400 with {"error":"invalid_token", ...} for invalid tokens.
      throw new IOException("Google tokeninfo rejected token: HTTP " + response.code);
    }
    JsonObject parsed;
    try {
      parsed = JSON.newGson().fromJson(response.body, JsonObject.class);
    } catch (JsonSyntaxException e) {
      throw new IOException("Google tokeninfo response is not valid JSON", e);
    }
    if (parsed == null) {
      throw new IOException("Google tokeninfo response is empty");
    }
    return parsed;
  }

  private void verifyAudience(JsonObject response) throws IOException {
    if (trustedAudiences.isEmpty()) {
      throw new IOException("Google client-id not configured; cannot verify token audience");
    }
    // Google's access-token tokeninfo uses `audience`; the OIDC-style response uses `aud`.
    String aud = firstString(response, "aud", "audience");
    if (aud == null || !trustedAudiences.contains(aud)) {
      throw new IOException("Token audience does not match a trusted client id");
    }
    // Refuse cross-client tokens: authorized for another app but carrying us in the audience.
    // `azp` (OIDC) / `issued_to` (access-token tokeninfo) name the authorized party.
    String azp = firstString(response, "azp", "issued_to");
    if (azp != null && !trustedAudiences.contains(azp)) {
      throw new IOException("Token authorized party does not match a trusted client id");
    }
  }

  private static void verifyEmailVerified(JsonObject response) throws IOException {
    // `email_verified` (OIDC) / `verified_email` (access-token tokeninfo); Google sends booleans as
    // strings.
    String verified = firstString(response, "email_verified", "verified_email");
    if (verified == null) {
      throw new IOException("Google tokeninfo response is missing email_verified/verified_email");
    }
    if (!"true".equalsIgnoreCase(verified)) {
      throw new IOException("Google email is not verified");
    }
  }

  private static Optional<Long> extractExpiresAtMillis(JsonObject response) {
    // Absolute exp (epoch seconds) in the OIDC-style response.
    Optional<Long> exp = asLong(response.get("exp"));
    if (exp.isPresent()) {
      return Optional.of(exp.get() * 1000L);
    }
    // Relative expires_in (seconds from now) in the access-token tokeninfo response.
    Optional<Long> expiresIn = asLong(response.get("expires_in"));
    if (expiresIn.isPresent()) {
      return Optional.of(System.currentTimeMillis() + expiresIn.get() * 1000L);
    }
    // Without any expiry we have no upper bound on validity, so don't cache.
    return Optional.empty();
  }

  // Not shared with GoogleOAuthService via a common mapper on purpose: tokeninfo returns
  // `sub`/`user_id` and no display name, whereas userinfo returns `id`/`name` -- different shapes.
  private OAuthUserInfo parseUserClaims(JsonObject claimObject) throws IOException {
    // `sub` (OIDC) / `user_id` (access-token tokeninfo) is Google's stable account id.
    String sub = firstString(claimObject, "sub", "user_id");
    if (sub == null) {
      throw new IOException("Google tokeninfo response is missing the sub/user_id claim");
    }
    String email = firstString(claimObject, "email");
    if (email == null) {
      throw new IOException("Google tokeninfo response is missing the email claim");
    }
    String login = useEmailAsUsername ? email.split("@")[0] : null;
    if (log.isDebugEnabled()) {
      log.debug("Tokeninfo validated sub={}", sub);
    }
    return new OAuthUserInfo(
        extIdScheme + ":" + sub,
        login,
        email,
        null /* tokeninfo does not return a display name */,
        fixLegacyUserId ? sub : null);
  }

  private static Optional<Long> asLong(JsonElement e) {
    if (e == null || !e.isJsonPrimitive()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(e.getAsString()));
    } catch (NumberFormatException ignored) {
      // fall through to the numeric form
    }
    return e.getAsJsonPrimitive().isNumber() ? Optional.of(e.getAsLong()) : Optional.empty();
  }
}
