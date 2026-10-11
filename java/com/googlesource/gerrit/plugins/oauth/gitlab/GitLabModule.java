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

package com.googlesource.gerrit.plugins.oauth.gitlab;

import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.googlesource.gerrit.plugins.oauth.base.GitOverHttpModule;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidator;

/** Wires GitLab's Git-over-HTTP token validator and login provider. */
public class GitLabModule extends GitOverHttpModule {
  @Override
  protected String providerName() {
    return GitLabOAuthService.PROVIDER_NAME;
  }

  @Override
  protected Class<? extends OAuthTokenValidator> validatorClass() {
    return GitLabTokenInfoValidator.class;
  }

  @Override
  protected Class<? extends OAuthLoginProvider> loginProviderClass() {
    return GitLabOAuthLoginProvider.class;
  }
}
