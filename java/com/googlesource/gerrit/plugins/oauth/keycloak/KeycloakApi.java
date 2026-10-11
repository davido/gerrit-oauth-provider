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

/** Keycloak realm OAuth endpoint URLs, plus the issuer and JWKS used for id_token validation. */
public class KeycloakApi {

  private static final String AUTHORIZE_URL = "%s/realms/%s/protocol/openid-connect/auth";

  private final String rootUrl;
  private final String realm;

  public KeycloakApi(String rootUrl, String realm) {
    this.rootUrl = rootUrl;
    this.realm = realm;
  }

  public String getAuthorizationBaseUrl() {
    return String.format(AUTHORIZE_URL, rootUrl, realm);
  }

  public String getAccessTokenEndpoint() {
    return String.format("%s/realms/%s/protocol/openid-connect/token", rootUrl, realm);
  }

  /** Realm OAuth 2.0 token revocation endpoint (RFC 7009). */
  public String getRevocationEndpoint() {
    return String.format("%s/realms/%s/protocol/openid-connect/revoke", rootUrl, realm);
  }

  /** Realm issuer URL — the {@code iss} claim in Keycloak's JWTs. */
  public String getIssuer() {
    return String.format("%s/realms/%s", rootUrl, realm);
  }

  /** Realm JWKS endpoint — public keys for verifying Keycloak's JWT signatures. */
  public String getJwksEndpoint() {
    return String.format("%s/realms/%s/protocol/openid-connect/certs", rootUrl, realm);
  }
}
