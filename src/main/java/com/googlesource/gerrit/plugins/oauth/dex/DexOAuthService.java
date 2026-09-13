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

package com.googlesource.gerrit.plugins.oauth.dex;

import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonElement;
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
@OAuthServiceProviderConfig(name = DexOAuthService.PROVIDER_NAME)
public class DexOAuthService extends StandardIdTokenOAuthService {
  public static final String PROVIDER_NAME = "dex";
  private final String domain;
  private final String extIdScheme;
  @Nullable private final OidcJwtValidator validator;

  @Inject
  DexOAuthService(OAuthPluginConfigFactory cfgFactory, HttpOAuthClientFactory clientFactory) {
    this(cfgFactory, clientFactory, /* providedValidator= */ null);
  }

  @VisibleForTesting
  DexOAuthService(
      OAuthPluginConfigFactory cfgFactory,
      HttpOAuthClientFactory clientFactory,
      @Nullable OidcJwtValidator providedValidator) {
    super(cfgFactory.create(PROVIDER_NAME).getString(OAuthConfigKeys.SERVICE_NAME, "Dex OAuth2"));
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    String rootUrl = OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL));
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
    domain = cfg.getString(OAuthConfigKeys.DOMAIN, null);
    boolean enablePkce = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    DexApi api = new DexApi(rootUrl);
    // Descriptor: default HTTP Basic client auth, JSON token response, and the bearer as an
    // access_token query parameter. DexApi is kept
    // for the issuer/JWKS the id_token validator below reads.
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            api.getAuthorizationBaseUrl(),
            api.getAccessTokenEndpoint(),
            "openid profile email offline_access",
            ClientAuthStyle.BASIC,
            BearerPlacement.URI_QUERY_ACCESS_TOKEN,
            TokenResponseFormat.JSON,
            /* tolerateMissingTokenType= */ false,
            enablePkce);
    client = clientFactory.create(PROVIDER_NAME, endpoints);
    extIdScheme = OAuthServiceProviderExternalIdScheme.create(PROVIDER_NAME);
    this.validator =
        providedValidator != null
            ? providedValidator
            : buildValidator(api, cfg.getString(OAuthConfigKeys.CLIENT_ID));
  }

  /**
   * Builds a JWKS validator for Dex's {@code id_token}, with issuer and JWKS taken from {@link
   * DexApi} (both under the {@code /dex} path its endpoints live at) and audience pinned to {@code
   * client-id}. Returns {@code null} only when {@code client-id} is absent, in which case the
   * id_token is refused rather than trusted unsigned (client-id is required to build the client).
   */
  @Nullable
  private OidcJwtValidator buildValidator(DexApi api, @Nullable String clientId) {
    if (Strings.isNullOrEmpty(clientId)) {
      log.warn("Dex client-id is not configured; id_token signature validation is disabled");
      return null;
    }
    return OidcJwtValidator.builder()
        .jwksUri(api.getJwksEndpoint())
        .issuer(api.getIssuer())
        .audience(clientId)
        .build();
  }

  /**
   * Verifies the {@code id_token} against Dex's JWKS instead of the base class's unsigned decode.
   */
  @Override
  protected JsonObject decodeIdToken(String idToken) throws IOException {
    if (validator == null) {
      throw new IOException("Dex id_token cannot be validated because client-id is not configured");
    }
    return validator.validate(idToken).payload();
  }

  @Override
  protected OAuthUserInfo parseClaims(JsonObject claimObject) throws IOException {
    // Dex does not support basic profile currently (2017-09), extracting info
    // from access token claim
    JsonElement emailElement = claimObject.get("email");
    JsonElement nameElement = claimObject.get("name");
    if (isNull(emailElement)) {
      throw new IOException("Response doesn't contain email field");
    }
    if (nameElement == null || nameElement.isJsonNull()) {
      throw new IOException("Response doesn't contain name field");
    }
    String email = emailElement.getAsString();
    String name = nameElement.getAsString();
    String username = email;
    if (domain != null && domain.length() > 0) {
      username = email.replace("@" + domain, "");
    }

    return new OAuthUserInfo(extIdScheme + ":" + email, username, email, name, null);
  }
}
