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

import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.CLIENT_ID;
import static com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys.CLIENT_SECRET;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.InitStep;
import com.google.gerrit.pgm.init.api.Section;
import com.google.inject.ProvisionException;
import java.net.URI;

/**
 * Provider-agnostic base for OAuth {@code init} steps.
 *
 * <p>The all-inclusive {@code oauth} plugin's {@link InitStep} runs a block per bundled provider; a
 * single-provider artifact runs only its own. All shared prompt helpers live here, so a provider
 * init step only declares its own questions in {@link #configure()}.
 */
public abstract class AbstractOAuthInitStep implements InitStep {

  protected static final String PLUGIN_SECTION = "plugin";
  protected static final String FIX_LEGACY_USER_ID_QUESTION =
      "Fix legacy user id, without oauth provider prefix?";

  protected final ConsoleUI ui;
  protected final Section.Factory sections;
  protected final String pluginName;

  protected AbstractOAuthInitStep(ConsoleUI ui, Section.Factory sections, String pluginName) {
    this.ui = ui;
    this.sections = sections;
    this.pluginName = pluginName;
  }

  @Override
  public void run() throws Exception {
    ui.header("OAuth Authentication Provider");
    configure();
  }

  /** Prompts for the providers this artifact bundles. */
  public abstract void configure() throws Exception;

  @Override
  public void postRun() throws Exception {}

  /** True if the provider section already has a client id, i.e. was configured before. */
  protected static boolean isConfigured(Section s) {
    return !Strings.isNullOrEmpty(s.get(CLIENT_ID));
  }

  /** Prompts for client id/secret; returns true if a client id was provided. */
  protected static boolean configureOAuth(Section s) {
    if (!Strings.isNullOrEmpty(s.string("Application client id", CLIENT_ID, null))) {
      s.passwordForKey("Application client secret", CLIENT_SECRET);
      return true;
    }
    return false;
  }

  /** Requires a non-null, absolute root URL. */
  protected static void checkRootUrl(String rootUrl) {
    requireNonNull(rootUrl);
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
  }

  protected Section getConfigSection(Class<? extends OAuthServiceProvider> serviceClass) {
    return getConfigSection(serviceClass.getAnnotation(OAuthServiceProviderConfig.class).name());
  }

  protected Section getConfigSection(String serviceProviderName) {
    return sections.get(PLUGIN_SECTION, pluginName + "-" + serviceProviderName + "-oauth");
  }
}
