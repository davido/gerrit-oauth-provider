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

import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ENABLE_GIT_OVER_HTTP;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ENABLE_PKCE;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.ROOT_URL;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.Section;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.oauth.base.AbstractOAuthInitStep;

/** {@code Gerrit-InitStep} for the standalone {@code oauth-gitlab} plugin artifact. */
public class GitLabInitStep extends AbstractOAuthInitStep {

  @Inject
  public GitLabInitStep(ConsoleUI ui, Section.Factory sections, @PluginName String pluginName) {
    super(ui, sections, pluginName);
  }

  @Override
  public void configure() {
    Section s = getConfigSection(GitLabOAuthService.class);
    if (ui.yesno(isConfigured(s), "Use GitLab OAuth provider for Gerrit login?")
        && configureOAuth(s)) {
      checkRootUrl(s.string("GitLab Root URL", ROOT_URL, null));
      s.string("Enable PKCE for GitLab OAuth provider?", ENABLE_PKCE, "false");
      s.string("Enable Git-over-HTTP for GitLab OAuth provider?", ENABLE_GIT_OVER_HTTP, "false");
    }
  }
}
