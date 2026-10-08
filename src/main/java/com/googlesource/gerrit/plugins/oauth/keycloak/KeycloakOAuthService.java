// Copyright (C) 2017 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.keycloak;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderConfig;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.googlesource.gerrit.plugins.oauth.base.StandardIdTokenOAuthService;
import com.googlesource.gerrit.plugins.oauth.client.BearerPlacement;
import com.googlesource.gerrit.plugins.oauth.client.ClientAuthStyle;
import com.googlesource.gerrit.plugins.oauth.client.OAuthProviderEndpoints;
import com.googlesource.gerrit.plugins.oauth.client.TokenResponseFormat;
import com.googlesource.gerrit.plugins.oauth.jwt.OidcJwtValidator;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import java.io.IOException;
import java.net.URI;

@Singleton
@OAuthServiceProviderConfig(name = KeycloakOAuthService.PROVIDER_NAME)
public class KeycloakOAuthService extends StandardIdTokenOAuthService {
  public static final String PROVIDER_NAME = "keycloak";
  private final OidcJwtValidator validator;
  private final KeycloakUserInfoMapper userInfoMapper;
  private final boolean refreshEnabled;

  @Inject
  KeycloakOAuthService(OAuthPluginConfigFactory cfgFactory, HttpOAuthClientFactory clientFactory) {
    this(cfgFactory, clientFactory, /* providedValidator= */ null);
  }

  @VisibleForTesting
  KeycloakOAuthService(
      OAuthPluginConfigFactory cfgFactory,
      HttpOAuthClientFactory clientFactory,
      @Nullable OidcJwtValidator providedValidator) {
    super(
        cfgFactory
            .create(PROVIDER_NAME)
            .getString(OAuthConfigKeys.SERVICE_NAME, "Keycloak OAuth2"));
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    String rootUrl = OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL));
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
    String realm = cfg.getString(OAuthConfigKeys.REALM);
    boolean usePreferredUsername = cfg.getBoolean(OAuthConfigKeys.USE_PREFERRED_USERNAME, true);
    boolean linkToExistingGerrit =
        cfg.getBoolean(OAuthConfigKeys.LINK_TO_EXISTING_GERRIT_ACCOUNT, false);
    boolean enablePKCE = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    this.refreshEnabled = cfg.getBoolean(OAuthConfigKeys.ENABLE_TOKEN_REFRESH, false);
    String clientId =
        requireNonNull(cfg.getString(OAuthConfigKeys.CLIENT_ID), "client-id is required");
    KeycloakApi api = new KeycloakApi(rootUrl, realm);
    // Keycloak: request-body client auth, JSON token response, realm-derived authorize/token URLs.
    // id_token provider (no resource GET), so bearer placement is unused; kept as query-param to
    // mirror KeycloakApi, which still supplies the issuer/JWKS URLs for the id_token validator.
    // offline_access is not forced; the auth-code flow already returns a session refresh_token.
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            api.getAuthorizationBaseUrl(),
            api.getAccessTokenEndpoint(),
            "openid",
            ClientAuthStyle.REQUEST_BODY,
            BearerPlacement.URI_QUERY_ACCESS_TOKEN,
            TokenResponseFormat.JSON,
            /* tolerateMissingTokenType= */ false,
            enablePKCE,
            api.getRevocationEndpoint());
    client = clientFactory.create(PROVIDER_NAME, endpoints);
    if (providedValidator != null) {
      this.validator = providedValidator;
    } else {
      this.validator =
          OidcJwtValidator.builder()
              .jwksUri(api.getJwksEndpoint())
              .issuer(api.getIssuer())
              .audience(clientId)
              .build();
    }
    userInfoMapper =
        new KeycloakUserInfoMapper(
            usePreferredUsername,
            OAuthServiceProviderExternalIdScheme.create(PROVIDER_NAME),
            linkToExistingGerrit);
  }

  /** Verifies the {@code id_token} signature against the realm's JWKS before reading its claims. */
  @Override
  protected JsonObject decodeIdToken(String idToken) throws IOException {
    return validator.validate(idToken).payload();
  }

  @Override
  protected OAuthUserInfo parseClaims(JsonObject claimObject) throws IOException {
    return userInfoMapper.mapForBrowser(claimObject);
  }

  @Override
  public boolean supportsRefresh() {
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
