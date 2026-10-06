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

package com.googlesource.gerrit.plugins.oauth.client;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthAuthorizationInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * Executes OAuth protocol operations for one configured provider.
 *
 * <p>Implementations MUST NOT retain per-authorization state. Concurrent authorization flows must
 * be fully independent: any {@code state}, {@code code_verifier} or {@code nonce} belongs to a
 * single login attempt and must be carried in {@link OAuthAuthorizationInfo} or passed as a
 * parameter, never stored on the instance.
 */
public interface OAuthClient {
  /**
   * @return the URL to redirect the user to, plus any per-login PKCE verifier.
   */
  OAuthAuthorizationInfo getAuthorizationInfo();

  /**
   * Exchanges an authorization code for an access token.
   *
   * @param verifier the authorization code returned by the provider
   * @param codeVerifier the PKCE verifier generated during authorization, or {@code null}
   * @return the access token
   */
  OAuthToken exchangeCode(OAuthVerifier verifier, @Nullable String codeVerifier) throws IOException;

  /**
   * Exchanges resource-owner credentials for an access token (OAuth 2.0 password grant).
   *
   * @return the access token
   */
  OAuthToken passwordGrant(String username, String password) throws IOException;

  /**
   * Exchanges the refresh token carried in {@code expiredToken.getRaw()} for a fresh access token
   * (OAuth 2.0 refresh grant, RFC 6749 §6).
   *
   * @return a new access token with {@code expiresAt} populated; the prior refresh token is carried
   *     forward when the response omits one.
   * @throws OAuthRevokedException when the IdP replies {@code invalid_grant} (revoke the session).
   * @throws IOException on a transient IdP/network failure (handle by policy, do not revoke).
   */
  OAuthToken refresh(OAuthToken expiredToken) throws IOException;

  /**
   * Revokes a token at the identity provider (OAuth 2.0 Token Revocation, RFC 7009 §2.1) by POSTing
   * it to the provider's configured {@code revocationEndpoint}. Prefers the refresh token carried
   * in {@code token.getRaw()} (revoking it invalidates the whole grant); falls back to the access
   * token. Per RFC 7009 §2.2 an unknown or already-invalid token is a successful no-op.
   *
   * @param token the token whose grant should be revoked
   * @throws IOException on a transient IdP/network failure
   * @throws UnsupportedOperationException if the provider has no revocation endpoint configured
   */
  void revoke(OAuthToken token) throws IOException;

  /** Whether a revocation endpoint is configured, i.e. {@link #revoke} is usable. */
  boolean supportsRevoke();

  /**
   * Fetches a protected resource with the given token.
   *
   * @return the response body; throws if the provider did not return success.
   */
  String get(URI resource, OAuthToken token) throws IOException;

  /**
   * Fetches a protected resource with the given token and extra request headers.
   *
   * @return the response body; throws if the provider did not return success.
   */
  String get(URI resource, OAuthToken token, Map<String, String> headers) throws IOException;

  /**
   * @return the OAuth version of the service, e.g. {@code "2.0"}.
   */
  String getVersion();
}
