// Copyright (C) 2015 The Android Open Source Project
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
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.asString;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthAuthorizationInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oauth.base.AbstractOAuthService;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderConfig;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.googlesource.gerrit.plugins.oauth.client.BearerPlacement;
import com.googlesource.gerrit.plugins.oauth.client.ClientAuthStyle;
import com.googlesource.gerrit.plugins.oauth.client.OAuthProviderEndpoints;
import com.googlesource.gerrit.plugins.oauth.client.TokenResponseFormat;
import com.googlesource.gerrit.plugins.oauth.jwt.OidcJwtValidator;
import com.googlesource.gerrit.plugins.oauth.jwt.ValidatedClaims;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Singleton
@OAuthServiceProviderConfig(name = GoogleOAuthService.PROVIDER_NAME)
public class GoogleOAuthService extends AbstractOAuthService {
  public static final String PROVIDER_NAME = "google";
  private static final String PROTECTED_RESOURCE_URL =
      "https://www.googleapis.com/oauth2/v2/userinfo";
  // `openid` makes Google return an id_token; `email profile` populate the userinfo response.
  private static final String SCOPE = "openid email profile";
  // Google accepts either issuer form below for its id_tokens.
  private static final String GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
  private static final String GOOGLE_ISSUER_HTTPS = "https://accounts.google.com";
  private static final String GOOGLE_ISSUER_BARE = "accounts.google.com";

  private final List<String> domains;
  private final boolean useEmailAsUsername;
  private final boolean fixLegacyUserId;
  private final boolean refreshEnabled;
  private final boolean forceConsent;
  private final String extIdScheme;
  @Nullable private final OidcJwtValidator idTokenValidator;

  @Inject
  GoogleOAuthService(OAuthPluginConfigFactory cfgFactory, HttpOAuthClientFactory clientFactory) {
    this(cfgFactory, clientFactory, /* providedIdTokenValidator= */ null);
  }

  @VisibleForTesting
  GoogleOAuthService(
      OAuthPluginConfigFactory cfgFactory,
      HttpOAuthClientFactory clientFactory,
      @Nullable OidcJwtValidator providedIdTokenValidator) {
    super("Google OAuth2");
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    if (cfg.getBoolean(OAuthConfigKeys.LINK_TO_EXISTING_OPENID_ACCOUNT, false)) {
      log.warn(
          String.format(
              "The support for: %s is disconinued",
              OAuthConfigKeys.LINK_TO_EXISTING_OPENID_ACCOUNT));
    }
    fixLegacyUserId = cfg.getBoolean(OAuthConfigKeys.FIX_LEGACY_USER_ID, false);
    this.domains = Arrays.asList(cfg.getStringList(OAuthConfigKeys.DOMAIN));
    this.useEmailAsUsername = cfg.getBoolean(OAuthConfigKeys.USE_EMAIL_AS_USERNAME, false);
    this.refreshEnabled = cfg.getBoolean(OAuthConfigKeys.ENABLE_TOKEN_REFRESH, false);
    this.forceConsent = cfg.getBoolean(OAuthConfigKeys.FORCE_CONSENT, false);
    boolean enablePkce = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    // Google: default HTTP Basic client auth, JSON token response, header-bearer
    // userinfo GET, openid email profile scope. The hosted-domain (hd) authorization parameter is
    // appended by getAuthorizationInfo() below -- not by the client -- so no descriptor change is
    // needed for it. Google2Api still supplies the fixed endpoints and the tokeninfo URL (Git
    // path).
    Google2Api api = new Google2Api();
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            api.getAuthorizationBaseUrl(),
            api.getAccessTokenEndpoint(),
            SCOPE,
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            /* tolerateMissingTokenType= */ false,
            enablePkce,
            api.getRevocationEndpoint());
    this.client = clientFactory.create(PROVIDER_NAME, endpoints);
    extIdScheme = OAuthServiceProviderExternalIdScheme.create(PROVIDER_NAME);

    String expectedAudience =
        GoogleClientId.normalizeAudience(cfg.getString(OAuthConfigKeys.CLIENT_ID));
    if (providedIdTokenValidator != null) {
      this.idTokenValidator = providedIdTokenValidator;
    } else if (expectedAudience != null) {
      this.idTokenValidator =
          OidcJwtValidator.builder()
              .jwksUri(GOOGLE_JWKS_URI)
              .issuer(GOOGLE_ISSUER_HTTPS)
              .issuer(GOOGLE_ISSUER_BARE)
              .audience(expectedAudience)
              .build();
    } else {
      this.idTokenValidator = null;
      log.warn("OAuth2: Google client-id not configured; id_token validation disabled.");
    }

    if (log.isDebugEnabled()) {
      log.debug("OAuth2: scope={}", SCOPE);
      log.debug("OAuth2: domains={}", domains);
      log.debug("OAuth2: useEmailAsUsername={}", useEmailAsUsername);
    }
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    // Validate the id_token to get the verified sub (bound to the userinfo id below) and hd claim.
    ValidatedClaims idTokenClaims = validateIdTokenFromTokenResponse(token);

    String body = client.get(URI.create(PROTECTED_RESOURCE_URL), token);
    if (log.isDebugEnabled()) {
      log.debug("User info response: {}", body);
    }
    JsonElement userJson = JSON.newGson().fromJson(body, JsonElement.class);
    if (userJson.isJsonObject()) {
      JsonObject jsonObject = userJson.getAsJsonObject();
      JsonElement id = jsonObject.get("id");
      if (isNull(id)) {
        throw new IOException("Response doesn't contain id field");
      }
      if (!id.getAsString().equals(idTokenClaims.subject())) {
        throw new IOException(
            "Subject mismatch: the verified id_token subject does not match the userinfo id");
      }
      JsonElement email = jsonObject.get("email");
      JsonElement name = jsonObject.get("name");
      String login = null;

      if (!domains.isEmpty()) {
        boolean domainMatched = false;
        String hdClaim = retrieveHostedDomain(idTokenClaims);
        for (String domain : domains) {
          if (domain.equalsIgnoreCase(hdClaim)) {
            domainMatched = true;
            break;
          }
        }
        if (!domainMatched) {
          // TODO(davido): improve error reporting in OAuth extension point
          log.error("Error: hosted domain validation failed: {}", Strings.nullToEmpty(hdClaim));
          return null;
        }
      }
      if (useEmailAsUsername && !email.isJsonNull()) {
        login = email.getAsString().split("@")[0];
      }
      // Not shared with GoogleTokenInfoValidator (Git path) via a common mapper on purpose:
      // userinfo returns `id`/`name`, tokeninfo returns `sub`/`user_id` and no name.
      return new OAuthUserInfo(
          extIdScheme + ":" + id.getAsString(),
          login,
          asString(email),
          asString(name),
          fixLegacyUserId ? id.getAsString() : null);
    }

    throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
  }

  /** Validates the id_token against Google's JWKS, requiring a {@code sub} claim. */
  private ValidatedClaims validateIdTokenFromTokenResponse(OAuthToken token) throws IOException {
    if (idTokenValidator == null) {
      throw new IOException("Google id_token validator not configured (missing client-id)");
    }
    JsonElement raw = JSON.newGson().fromJson(token.getRaw(), JsonElement.class);
    if (raw == null || !raw.isJsonObject()) {
      throw new IOException("Token response is not a JSON object");
    }
    JsonElement idToken = raw.getAsJsonObject().get("id_token");
    if (isNull(idToken)) {
      throw new IOException("Token response is missing id_token");
    }
    ValidatedClaims claims = idTokenValidator.validate(idToken.getAsString());
    if (claims.subject() == null || claims.subject().isBlank()) {
      throw new IOException("id_token is missing the required sub claim");
    }
    return claims;
  }

  private String retrieveHostedDomain(ValidatedClaims claims) {
    JsonElement hd = claims.payload().get("hd");
    if (isNull(hd)) {
      log.debug("OAuth2: id_token does not contain hd claim");
      return null;
    }
    String value = hd.getAsString();
    log.debug("OAuth2: hd={}", value);
    return value;
  }

  @Override
  public OAuthAuthorizationInfo getAuthorizationInfo() {
    OAuthAuthorizationInfo info = client.getAuthorizationInfo();
    StringBuilder urlBuilder = new StringBuilder(info.getAuthorizationUrl());
    if (domains.size() == 1) {
      urlBuilder.append("&hd=");
      urlBuilder.append(URLEncoder.encode(domains.get(0), StandardCharsets.UTF_8));
    } else if (domains.size() > 1) {
      urlBuilder.append("&hd=*");
    }
    if (refreshEnabled) {
      // Google issues a refresh_token only when access_type=offline is requested. prompt=consent
      // forces re-issuance (Google returns a refresh_token only on first consent).
      urlBuilder.append("&access_type=offline");
      if (forceConsent) {
        urlBuilder.append("&prompt=consent");
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: authorization URL={}", urlBuilder);
    }
    return new OAuthAuthorizationInfo(urlBuilder.toString(), info.getPkceVerifier());
  }

  @Override
  public boolean supportsRefresh() {
    // Refreshable only when configured to request a refresh token (enable-token-refresh).
    return refreshEnabled;
  }

  @Override
  public OAuthToken refresh(OAuthToken token) throws IOException {
    return client.refresh(token);
  }

  @Override
  public boolean supportsRevoke() {
    return client.supportsRevoke();
  }

  @Override
  public void revoke(OAuthToken token) throws IOException {
    client.revoke(token);
  }
}
