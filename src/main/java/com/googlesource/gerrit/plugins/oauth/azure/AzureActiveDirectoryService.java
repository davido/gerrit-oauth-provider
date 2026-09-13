// Copyright (C) 2018 The Android Open Source Project
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
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.jwtPayloadJson;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
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
import java.io.IOException;
import java.net.URI;

@Singleton
@OAuthServiceProviderConfig(name = AzureActiveDirectoryService.PROVIDER_NAME)
public class AzureActiveDirectoryService extends AbstractOAuthService {
  // Canonical provider name (Azure AD)
  public static final String PROVIDER_NAME = "azure";
  // Deprecated provider name kept for backward compatibility
  static final String PROVIDER_DEPRECATED_NAME = "office365";
  private static final String PROTECTED_RESOURCE_URL = "https://graph.microsoft.com/v1.0/me";
  private static final String AUTHORIZATION_URL =
      "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize";
  private static final String ACCESS_TOKEN_URL =
      "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
  private static final String SCOPE =
      "openid offline_access https://graph.microsoft.com/user.readbasic.all";
  public static final String DEFAULT_TENANT = "organizations";
  static final ImmutableSet<String> TENANTS_WITHOUT_VALIDATION =
      ImmutableSet.<String>builder().add(DEFAULT_TENANT).add("common").add("consumers").build();
  private final Gson gson;
  private final String tenant;
  private final String clientId;
  private final AzureUserInfoMapper userInfoMapper;
  @Nullable private final OidcJwtValidator validator;

  @Inject
  AzureActiveDirectoryService(
      OAuthPluginConfigFactory cfgFactory, HttpOAuthClientFactory clientFactory) {
    this(cfgFactory, clientFactory, /* providedValidator= */ null);
  }

  @VisibleForTesting
  AzureActiveDirectoryService(
      OAuthPluginConfigFactory cfgFactory,
      HttpOAuthClientFactory clientFactory,
      @Nullable OidcJwtValidator providedValidator) {
    super("Office365 OAuth2");
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    // Treat a blank tenant like an absent one and fall back to the default tenant; an empty tenant
    // would otherwise produce a malformed `https://login.microsoftonline.com//oauth2/...` endpoint.
    String configuredTenant = cfg.getString(OAuthConfigKeys.TENANT, DEFAULT_TENANT);
    this.tenant = Strings.isNullOrEmpty(configuredTenant) ? DEFAULT_TENANT : configuredTenant;
    this.clientId = cfg.getString(OAuthConfigKeys.CLIENT_ID);
    boolean useEmailAsUsername = cfg.getBoolean(OAuthConfigKeys.USE_EMAIL_AS_USERNAME, false);
    boolean linkOffice365Id =
        cfg.getBoolean(OAuthConfigKeys.LINK_TO_EXISTING_OFFICE365_ACCOUNT, false);
    this.userInfoMapper =
        new AzureUserInfoMapper(
            OAuthServiceProviderExternalIdScheme.create(PROVIDER_NAME),
            OAuthServiceProviderExternalIdScheme.create(PROVIDER_DEPRECATED_NAME),
            useEmailAsUsername,
            linkOffice365Id);
    boolean enablePkce = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    // Descriptor: request-body client auth (Azure v2.0 expects client_secret in the token
    // request body), JSON token response, header bearer, tenant-scoped v2.0 endpoints. The Graph
    // /me fetch and its Accept header live in getUserInfo below; the id_token is validated there
    // too (fixed tenant) before the resource call.
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            String.format(AUTHORIZATION_URL, tenant),
            String.format(ACCESS_TOKEN_URL, tenant),
            SCOPE,
            ClientAuthStyle.REQUEST_BODY,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            /* tolerateMissingTokenType= */ false,
            enablePkce);
    this.client = clientFactory.create(PROVIDER_NAME, endpoints);
    this.gson = JSON.newGson();
    this.validator = providedValidator != null ? providedValidator : buildValidatorIfFixedTenant();
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: scope={}", SCOPE);
      log.debug("OAuth2: useEmailAsUsername={}", useEmailAsUsername);
    }
  }

  /**
   * Builds a JWKS id_token validator for a fixed tenant -- issuer and JWKS pinned to {@code
   * login.microsoftonline.com/<tenant>}, audience pinned to {@code client-id}. Returns {@code null}
   * for the multi-tenant aliases ({@code organizations}/{@code common}/{@code consumers}), whose
   * issuer varies per user and needs Azure-specific key-issuer handling; browser id_token DiD is
   * therefore only active for a fixed tenant.
   *
   * <p>A fixed tenant must be the tenant <b>GUID</b>: the pinned issuer is {@code
   * https://login.microsoftonline.com/<tenant-guid>/v2.0} and the {@code tid} claim is a GUID, so a
   * domain-form tenant ({@code contoso.onmicrosoft.com}) would mismatch both the issuer and the
   * {@code tid == tenant} check (the latter was already GUID-only before this change).
   */
  @Nullable
  private OidcJwtValidator buildValidatorIfFixedTenant() {
    if (TENANTS_WITHOUT_VALIDATION.contains(tenant)) {
      log.info(
          "Azure browser id_token JWKS validation is disabled for the multi-tenant alias '{}';"
              + " it is only active for a fixed tenant.",
          tenant);
      return null;
    }
    if (Strings.isNullOrEmpty(clientId)) {
      throw new ProvisionException(
          "Azure fixed-tenant login requires a non-blank client-id to validate the id_token"
              + " audience");
    }
    String base = "https://login.microsoftonline.com/" + tenant;
    return OidcJwtValidator.builder()
        .jwksUri(base + "/discovery/v2.0/keys")
        .issuer(base + "/v2.0")
        .audience(clientId)
        .build();
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    if (!verifyIdToken(token)) {
      // Return null so the user will be shown Unauthorized.
      return null;
    }

    String body =
        client.get(URI.create(PROTECTED_RESOURCE_URL), token, ImmutableMap.of("Accept", "*/*"));
    if (log.isDebugEnabled()) {
      log.debug("User info response: {}", body);
    }
    JsonElement userJson = JSON.newGson().fromJson(body, JsonElement.class);
    if (userJson.isJsonObject()) {
      return userInfoMapper.mapForBrowser(userJson.getAsJsonObject());
    }
    throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
  }

  /**
   * Authorizes the token before the Graph call. For a fixed tenant this cryptographically validates
   * the id_token (signature + {@code aud == client-id} + {@code exp} + pinned issuer) and binds the
   * verified {@code tid} to the configured tenant. For the multi-tenant aliases (validator absent)
   * it preserves the legacy unverified parse -- browser id_token JWKS validation is only active for
   * a fixed tenant. Returns {@code false} when the token must be rejected.
   */
  private boolean verifyIdToken(OAuthToken token) throws IOException {
    if (validator != null) {
      JsonObject claims = validator.validate(extractIdToken(token)).payload();
      JsonElement tidEl = claims.get("tid");
      String tid = isNull(tidEl) ? null : tidEl.getAsString();
      if (!tenant.equals(tid)) {
        log.warn("The id_token tid [{}] does not match the configured tenant [{}]", tid, tenant);
        return false;
      }
      return true;
    }
    // Multi-tenant alias: no single issuer to pin, so keep the legacy unverified checks. The
    // id_token is extracted from the raw token response.
    if (!TENANTS_WITHOUT_VALIDATION.contains(tenant)) {
      String tid = getTokenJson(token.getToken()).get("tid").getAsString();
      if (!tenant.equals(tid)) {
        log.warn(
            "The token was issued by the tenant [{}] while we are set to use [{}]", tid, tenant);
        return false;
      }
    }
    String aud = getTokenJson(extractIdToken(token)).get("aud").getAsString();
    if (!clientId.equals(aud)) {
      log.warn("The id_token had aud [{}] while we expected the clientId [{}]", aud, clientId);
      return false;
    }
    return true;
  }

  /** Extracts the base64url id_token from the raw token response. */
  private String extractIdToken(OAuthToken token) throws IOException {
    JsonObject jwtJson = gson.fromJson(token.getRaw(), JsonObject.class);
    JsonElement idToken = jwtJson == null ? null : jwtJson.get("id_token");
    if (isNull(idToken)) {
      throw new IOException("Token response is missing id_token");
    }
    return idToken.getAsString();
  }

  /** Get the {@link JsonObject} of a given token. */
  private JsonObject getTokenJson(String tokenBase64) {
    try {
      return gson.fromJson(jwtPayloadJson(tokenBase64), JsonObject.class);
    } catch (IOException e) {
      throw new IllegalStateException("Invalid token payload encoding", e);
    }
  }
}
