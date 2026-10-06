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

import static java.util.Objects.requireNonNull;

import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.inject.AbstractModule;
import java.util.function.Supplier;

public class SupportedLoginProvider {
  private final Class<? extends OAuthLoginProvider> loginProviderClass;
  private final Supplier<AbstractModule> moduleSupplier;
  private final boolean defaultGitOverHttp;

  public SupportedLoginProvider(
      Class<? extends OAuthLoginProvider> loginProviderClass,
      Supplier<AbstractModule> moduleSupplier,
      boolean defaultGitOverHttp) {
    this.loginProviderClass = requireNonNull(loginProviderClass, "loginProviderClass");
    this.moduleSupplier = requireNonNull(moduleSupplier, "moduleSupplier");
    this.defaultGitOverHttp = defaultGitOverHttp;
    requireNonNull(
        loginProviderClass.getAnnotation(OAuthServiceProviderConfig.class),
        () -> loginProviderClass.getName() + " is missing @OAuthServiceProviderConfig");
  }

  String name() {
    return loginProviderClass.getAnnotation(OAuthServiceProviderConfig.class).name();
  }

  AbstractModule module() {
    return moduleSupplier.get();
  }

  /** Default {@code enable-git-over-http} value used when the flag is omitted from config. */
  boolean defaultGitOverHttp() {
    return defaultGitOverHttp;
  }
}
