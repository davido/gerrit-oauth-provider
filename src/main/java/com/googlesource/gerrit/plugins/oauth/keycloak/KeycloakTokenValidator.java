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

package com.googlesource.gerrit.plugins.oauth.keycloak;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidator;
import com.googlesource.gerrit.plugins.oauth.jwt.OidcJwtValidator;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import java.io.IOException;

/**
 * Validates a Keycloak {@code access_token} (a realm-signed JWT) locally against the realm's JWKS,
 * then maps its claims via {@link KeycloakUserInfoMapper}. The realm must stamp this Gerrit's
 * {@code client-id} into the token's {@code aud}; the default {@code account} audience is rejected.
 */
@Singleton
class KeycloakTokenValidator implements OAuthTokenValidator {
  private final OidcJwtValidator validator;
  private final KeycloakUserInfoMapper userInfoMapper;

  @Inject
  KeycloakTokenValidator(OAuthPluginConfigFactory cfgFactory) {
    this(cfgFactory, /* providedValidator= */ null);
  }

  @VisibleForTesting
  KeycloakTokenValidator(
      OAuthPluginConfigFactory cfgFactory, @Nullable OidcJwtValidator providedValidator) {
    PluginConfig cfg = cfgFactory.create(KeycloakOAuthService.PROVIDER_NAME);
    String clientId =
        requireNonNull(cfg.getString(OAuthConfigKeys.CLIENT_ID), "client-id is required");
    boolean usePreferredUsername = cfg.getBoolean(OAuthConfigKeys.USE_PREFERRED_USERNAME, true);
    if (providedValidator != null) {
      this.validator = providedValidator;
    } else {
      KeycloakApi api =
          new KeycloakApi(
              OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL)),
              cfg.getString(OAuthConfigKeys.REALM));
      this.validator =
          OidcJwtValidator.builder()
              .jwksUri(api.getJwksEndpoint())
              .issuer(api.getIssuer())
              .audience(clientId)
              .build();
    }
    // the Git-over-HTTP path never links to existing accounts; validate() uses map(), which
    // ignores the flag, so pass false here.
    this.userInfoMapper =
        new KeycloakUserInfoMapper(
            usePreferredUsername,
            OAuthServiceProviderExternalIdScheme.create(KeycloakOAuthService.PROVIDER_NAME),
            /* linkToExistingGerrit= */ false);
  }

  @Override
  public OAuthUserInfo validate(String bearerToken) throws IOException {
    return userInfoMapper.map(validator.validate(bearerToken).payload());
  }
}
