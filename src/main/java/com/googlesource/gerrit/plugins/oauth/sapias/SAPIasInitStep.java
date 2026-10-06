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

package com.googlesource.gerrit.plugins.oauth.sapias;

import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ENABLE_GIT_OVER_HTTP;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ENABLE_PKCE;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ROOT_URL;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.Section;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.oauth.base.AbstractOAuthInitStep;

/** {@code Gerrit-InitStep} for the standalone {@code oauth-sapias} plugin artifact. */
public class SAPIasInitStep extends AbstractOAuthInitStep {

  @Inject
  public SAPIasInitStep(ConsoleUI ui, Section.Factory sections, @PluginName String pluginName) {
    super(ui, sections, pluginName);
  }

  @Override
  public void configure() {
    Section s = getConfigSection(SAPIasOAuthService.class);
    if (ui.yesno(isConfigured(s), "Use SAP IAS OAuth provider for Gerrit login?")
        && configureOAuth(s)) {
      checkRootUrl(s.string("SAP IAS Root URL", ROOT_URL, null));
      s.string("Enable PKCE for SAP IAS OAuth provider?", ENABLE_PKCE, "false");
      s.string("Enable Git-over-HTTP for SAP IAS OAuth provider?", ENABLE_GIT_OVER_HTTP, "true");
      s.string(
          "Enable SAP IAS resource-owner password flow?",
          "enable-resource-owner-password-flow",
          "false");
    }
  }
}
