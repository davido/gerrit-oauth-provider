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

package com.googlesource.gerrit.plugins.oauth.google;

import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.base.GitOverHttpModule;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidator;

/**
 * Wires Google's Git-over-HTTP token validator and login provider, plus the service-provider
 * binding the oauth-token SSH command injects to retrieve (and, if the provider supports it,
 * refresh) the caller's token. Installed only for the single provider with
 * enable-git-over-http=true.
 */
public class GoogleModule extends GitOverHttpModule {
  @Override
  protected String providerName() {
    return GoogleOAuthService.PROVIDER_NAME;
  }

  @Override
  protected Class<? extends OAuthTokenValidator> validatorClass() {
    return GoogleTokenInfoValidator.class;
  }

  @Override
  protected Class<? extends OAuthLoginProvider> loginProviderClass() {
    return GoogleOAuthLoginProvider.class;
  }
}
