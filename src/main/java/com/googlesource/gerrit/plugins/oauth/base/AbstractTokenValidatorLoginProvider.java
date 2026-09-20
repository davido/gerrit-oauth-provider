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

import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import java.io.IOException;

/** Base Git-over-HTTP login provider: validates the Basic-auth secret as a bearer token. */
public abstract class AbstractTokenValidatorLoginProvider implements OAuthLoginProvider {
  private final OAuthTokenValidator validator;

  protected AbstractTokenValidatorLoginProvider(OAuthTokenValidator validator) {
    this.validator = validator;
  }

  @Override
  public final OAuthUserInfo login(String username, String secret) throws IOException {
    if (secret == null) {
      throw new IOException("Authentication error");
    }
    OAuthUserInfo userInfo = validator.validate(secret);
    // If both a username and an IdP username are present, require them to match.
    String idpUsername = userInfo.getUserName();
    if (username != null && idpUsername != null && !username.equals(idpUsername)) {
      throw new IOException("Authentication error: username does not match");
    }
    return userInfo;
  }
}
