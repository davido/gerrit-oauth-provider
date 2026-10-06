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

package com.googlesource.gerrit.plugins.oauth.github;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCache;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidator;
import com.googlesource.gerrit.plugins.oauth.github.GitHubCheckTokenClient.Validated;
import java.io.IOException;
import java.util.Optional;

/** Git-over-HTTP validator: caches {@link GitHubCheckTokenClient} results. */
@Singleton
class GitHubCheckTokenValidator implements OAuthTokenValidator {
  private final GitHubCheckTokenClient client;
  private final OAuthTokenValidationCache validationCache;

  @Inject
  GitHubCheckTokenValidator(
      GitHubCheckTokenClient client, OAuthTokenValidationCache validationCache) {
    this.client = client;
    this.validationCache = validationCache;
  }

  @Override
  public OAuthUserInfo validate(String bearerToken) throws IOException {
    Optional<OAuthUserInfo> cached = validationCache.get(bearerToken);
    if (cached.isPresent()) {
      return cached.get();
    }
    Validated validated = client.validate(bearerToken);
    validated.expiresAtMillis.ifPresent(
        exp -> validationCache.put(bearerToken, validated.userInfo, exp));
    return validated.userInfo;
  }
}
