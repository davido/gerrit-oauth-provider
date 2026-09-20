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

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.inject.AbstractModule;

/** Base module binding a provider's Git-over-HTTP token validator and login provider. */
public abstract class GitOverHttpModule extends AbstractModule {
  protected abstract String providerName();

  protected abstract Class<? extends OAuthTokenValidator> validatorClass();

  protected abstract Class<? extends OAuthLoginProvider> loginProviderClass();

  @Override
  protected final void configure() {
    bind(OAuthTokenValidator.class).to(validatorClass());
    bind(OAuthLoginProvider.class)
        .annotatedWith(Exports.named(OAuthServiceProviderExternalIdScheme.create(providerName())))
        .to(loginProviderClass());
  }
}
