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

package com.googlesource.gerrit.plugins.oauth.airvantage;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.server.account.externalids.ExternalIdFactory;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.oauth.base.AbstractOAuthModule;
import com.googlesource.gerrit.plugins.oauth.base.SupportedLoginProvider;
import java.util.List;
import org.eclipse.jgit.lib.Config;

/** {@code Gerrit-Module} for the standalone {@code oauth-airvantage} plugin artifact. */
public class AirVantagePluginModule extends AbstractOAuthModule {

  @Inject
  public AirVantagePluginModule(
      @GerritServerConfig Config config,
      @PluginName String pluginName,
      ExternalIdFactory externalIdFactory) {
    super(config, pluginName, externalIdFactory);
  }

  @Override
  protected List<Class<? extends OAuthServiceProvider>> serviceProviders() {
    return List.of(AirVantageOAuthService.class);
  }

  @Override
  protected List<SupportedLoginProvider> loginProviders() {
    return List.of();
  }
}
