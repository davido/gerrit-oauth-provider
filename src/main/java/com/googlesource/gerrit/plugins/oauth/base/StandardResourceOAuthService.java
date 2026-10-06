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

package com.googlesource.gerrit.plugins.oauth.base;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.googlesource.gerrit.plugins.oauth.client.OAuthClient;
import java.io.IOException;
import java.net.URI;

/**
 * Base for providers that read the user information from a protected resource fetched with the
 * access token.
 *
 * <p>Subclasses supply only the resource URL ({@link #resourceUrl()}), the mapping of its response
 * ({@link #parseUserInfo(String)}), the service name, and construct the {@link OAuthClient}. Token
 * exchange, the authorization redirect, the version and the fetch itself are handled here and in
 * {@link AbstractOAuthService}, once.
 */
public abstract class StandardResourceOAuthService extends AbstractOAuthService {

  protected StandardResourceOAuthService(String name) {
    super(name);
  }

  /**
   * @return the protected resource URL to fetch the user information from.
   */
  protected abstract String resourceUrl();

  /** Maps the resource response body to Gerrit's user information. */
  protected abstract OAuthUserInfo parseUserInfo(String body) throws IOException;

  /**
   * Verifies the token before fetching the resource, returning the verified subject to bind against
   * {@link #resourceSubject}, or {@code null} for no verification (the default). Throwing rejects
   * the login.
   */
  @Nullable
  protected String verifyToken(OAuthToken token) throws IOException {
    return null;
  }

  /**
   * Extracts the subject from the resource body to cross-check against {@link #verifyToken}. Only
   * consulted when {@code verifyToken} returned a non-null subject; the default returns {@code
   * null}.
   */
  @Nullable
  protected String resourceSubject(String body) throws IOException {
    return null;
  }

  @Override
  public final OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    String verifiedSubject = verifyToken(token);
    String body = client.get(URI.create(resourceUrl()), token);
    if (log.isDebugEnabled()) {
      log.debug("User info response: {}", body);
    }
    // OIDC Core 5.3.2: a verified token subject must match the userinfo subject.
    if (verifiedSubject != null && !verifiedSubject.equals(resourceSubject(body))) {
      throw new IOException(
          "Subject mismatch: the verified token subject does not match the userinfo subject");
    }
    return parseUserInfo(body);
  }
}
