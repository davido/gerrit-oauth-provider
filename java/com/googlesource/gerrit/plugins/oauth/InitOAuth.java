// Copyright (C) 2015 The Android Open Source Project
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
package com.googlesource.gerrit.plugins.oauth;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.Section;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.oauth.airvantage.AirVantageInitStep;
import com.googlesource.gerrit.plugins.oauth.azure.AzureInitStep;
import com.googlesource.gerrit.plugins.oauth.base.AbstractOAuthInitStep;
import com.googlesource.gerrit.plugins.oauth.bitbucket.BitbucketInitStep;
import com.googlesource.gerrit.plugins.oauth.cas.CasInitStep;
import com.googlesource.gerrit.plugins.oauth.dex.DexInitStep;
import com.googlesource.gerrit.plugins.oauth.discovery.DiscoveryInitStep;
import com.googlesource.gerrit.plugins.oauth.facebook.FacebookInitStep;
import com.googlesource.gerrit.plugins.oauth.github.GitHubInitStep;
import com.googlesource.gerrit.plugins.oauth.gitlab.GitLabInitStep;
import com.googlesource.gerrit.plugins.oauth.google.GoogleInitStep;
import com.googlesource.gerrit.plugins.oauth.keycloak.KeycloakInitStep;
import com.googlesource.gerrit.plugins.oauth.phabricator.PhabricatorInitStep;

/**
 * All-inclusive {@code oauth} plugin init step. Delegates to every bundled provider's own init
 * step, the same ones the single-provider artifacts run.
 */
public class InitOAuth extends AbstractOAuthInitStep {

  @Inject
  InitOAuth(ConsoleUI ui, Section.Factory sections, @PluginName String pluginName) {
    super(ui, sections, pluginName);
  }

  @Override
  public void configure() throws Exception {
    new AirVantageInitStep(ui, sections, pluginName).configure();
    new AzureInitStep(ui, sections, pluginName).configure();
    new BitbucketInitStep(ui, sections, pluginName).configure();
    new CasInitStep(ui, sections, pluginName).configure();
    new DexInitStep(ui, sections, pluginName).configure();
    new DiscoveryInitStep(ui, sections, pluginName).configure();
    new FacebookInitStep(ui, sections, pluginName).configure();
    new GitHubInitStep(ui, sections, pluginName).configure();
    new GitLabInitStep(ui, sections, pluginName).configure();
    new GoogleInitStep(ui, sections, pluginName).configure();
    new KeycloakInitStep(ui, sections, pluginName).configure();
    new PhabricatorInitStep(ui, sections, pluginName).configure();
  }
}
