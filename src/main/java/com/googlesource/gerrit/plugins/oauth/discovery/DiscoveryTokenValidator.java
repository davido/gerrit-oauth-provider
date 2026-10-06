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

package com.googlesource.gerrit.plugins.oauth.discovery;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidator;
import com.googlesource.gerrit.plugins.oauth.jwt.OidcJwtValidator;
import java.io.IOException;

/**
 * Validates a Discovery {@code access_token} as a JWT via {@link OidcJwtValidator} (reused from
 * {@link DiscoveryOAuthService}), mapping claims through {@link DiscoveryUserInfoMapper}. Fails
 * fast at startup when the discovery document exposed no {@code jwks_uri}.
 */
@Singleton
class DiscoveryTokenValidator implements OAuthTokenValidator {
  private final OidcJwtValidator validator;
  private final DiscoveryUserInfoMapper userInfoMapper;

  @Inject
  DiscoveryTokenValidator(DiscoveryOAuthService service) {
    OidcJwtValidator jwtValidator = service.validator();
    if (jwtValidator == null) {
      throw new ProvisionException(
          "The configured discovery document does not expose jwks_uri, so bearer tokens cannot be"
              + " JWKS-validated for Git-over-HTTP. Use an OIDC-compliant IdP that publishes"
              + " jwks_uri, or set enable-git-over-http = false on the discovery subsection.");
    }
    this.validator = jwtValidator;
    // Reuse the service's mapper so the Git path honors external-id-scheme, matching browser login.
    this.userInfoMapper = service.userInfoMapper();
  }

  @Override
  public OAuthUserInfo validate(String bearerToken) throws IOException {
    return userInfoMapper.map(validator.validate(bearerToken).payload());
  }
}
