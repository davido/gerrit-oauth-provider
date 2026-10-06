// Copyright (C) 2025 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.sap;

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.inject.AbstractModule;
import com.google.inject.TypeLiteral;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.validation.CombiningValidator;

public class SAPIasModule extends AbstractModule {
  @Override
  public void configure() {
    String extIdScheme =
        OAuthServiceProviderExternalIdScheme.create(SAPIasOAuthService.PROVIDER_NAME);
    bind(new TypeLiteral<CombiningValidator<Token>>() {})
        .toProvider(SAPIasTokenValidatorProvider.class)
        .asEagerSingleton();
    bind(OAuthLoginProvider.class)
        .annotatedWith(Exports.named(extIdScheme))
        .to(SAPIasOAuthLoginProvider.class);
  }
}
