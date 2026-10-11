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
import com.google.gerrit.extensions.auth.oauth.OAuthAuthorizationInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.googlesource.gerrit.plugins.oauth.client.OAuthClient;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base for providers that delegate the OAuth protocol to an {@link OAuthClient}.
 *
 * <p>Token exchange, the authorization redirect, the version and the service name are handled here,
 * once, over the client. Subclasses construct the {@link #client} and supply {@code getUserInfo}
 * (directly, or via the {@link StandardResourceOAuthService} / {@link StandardIdTokenOAuthService}
 * specializations). A subclass may override {@link #getAuthorizationInfo()} when it must decorate
 * the redirect URL.
 */
public abstract class AbstractOAuthService implements OAuthServiceProvider {
  protected final Logger log = LoggerFactory.getLogger(getClass());

  /** The client for this provider; must be assigned by the subclass constructor. */
  protected OAuthClient client;

  private final String name;

  protected AbstractOAuthService(String name) {
    this.name = name;
  }

  @Override
  public OAuthToken getAccessToken(OAuthVerifier verifier, @Nullable String codeVerifier) {
    try {
      return client.exchangeCode(verifier, codeVerifier);
    } catch (IOException e) {
      String msg = "Cannot retrieve access token";
      log.error(msg, e);
      throw new RuntimeException(msg, e);
    }
  }

  @Override
  public OAuthAuthorizationInfo getAuthorizationInfo() {
    return client.getAuthorizationInfo();
  }

  @Override
  public String getVersion() {
    return client.getVersion();
  }

  @Override
  public String getName() {
    return name;
  }
}
