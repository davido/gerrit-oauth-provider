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

package com.googlesource.gerrit.plugins.oauth;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.inject.AbstractModule;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderConfig;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

public class ModuleTest {
  private static final String PLUGIN_NAME = "gerrit-oauth-provider";
  private static final String DISABLED = DisabledOAuthLoginProvider.class.getSimpleName();
  private static final String FAIL_FAST =
      "Multiple OAuth providers configured that support Git-over-HTTP";

  // "first" defaults Git-over-HTTP on (like SAP); "second" defaults off (opt-in).
  private static final List<SupportedLoginProvider> PROVIDERS =
      List.of(
          new SupportedLoginProvider(FirstLoginProvider.class, ModuleTest::emptyModule, true),
          new SupportedLoginProvider(SecondLoginProvider.class, ModuleTest::emptyModule, false));

  @Test
  public void grandfatheredProvider_omittedFlag_isSelected() {
    Config cfg = new Config();
    setClientId(cfg, "first");

    assertThat(elements(cfg)).doesNotContain(DISABLED);
  }

  @Test
  public void grandfatheredProvider_explicitlyDisabled_bindsDisabledProvider() {
    Config cfg = new Config();
    setClientId(cfg, "first");
    setGitOverHttp(cfg, "first", false);

    assertThat(elements(cfg)).contains(DISABLED);
  }

  @Test
  public void optInProvider_omittedFlag_notSelected() {
    Config cfg = new Config();
    setClientId(cfg, "second");

    assertThat(elements(cfg)).contains(DISABLED);
  }

  @Test
  public void optInProvider_flagEnabled_isSelected() {
    Config cfg = new Config();
    setClientId(cfg, "second");
    setGitOverHttp(cfg, "second", true);

    assertThat(elements(cfg)).doesNotContain(DISABLED);
  }

  @Test
  public void multipleEnabledProviders_failsFast() {
    Config cfg = new Config();
    setClientId(cfg, "first"); // grandfathered on
    setClientId(cfg, "second");
    setGitOverHttp(cfg, "second", true); // opt-in enabled

    assertThat(elements(cfg)).contains(FAIL_FAST + " (first, second)");
  }

  @Test
  public void singleEnabledProvider_doesNotFailFast() {
    Config cfg = new Config();
    setClientId(cfg, "first"); // grandfathered on
    setClientId(cfg, "second"); // configured but opt-in off -> not a Git-over-HTTP candidate

    assertThat(elements(cfg)).doesNotContain(FAIL_FAST);
  }

  private static String elements(Config cfg) {
    List<Element> elements = Elements.getElements(new Module(cfg, PLUGIN_NAME, null, PROVIDERS));
    return elements.toString();
  }

  private static void setClientId(Config cfg, String providerName) {
    cfg.setString(
        "plugin",
        PLUGIN_NAME + OAuthPluginConfigFactory.getConfigSuffix(providerName),
        OAuthConfigKeys.CLIENT_ID,
        "client-id");
  }

  private static void setGitOverHttp(Config cfg, String providerName, boolean value) {
    cfg.setBoolean(
        "plugin",
        PLUGIN_NAME + OAuthPluginConfigFactory.getConfigSuffix(providerName),
        OAuthConfigKeys.ENABLE_GIT_OVER_HTTP,
        value);
  }

  private static AbstractModule emptyModule() {
    return new AbstractModule() {};
  }

  @OAuthServiceProviderConfig(name = "first")
  private static class FirstLoginProvider implements OAuthLoginProvider {
    @Override
    public OAuthUserInfo login(String username, String secret) throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  @OAuthServiceProviderConfig(name = "second")
  private static class SecondLoginProvider implements OAuthLoginProvider {
    @Override
    public OAuthUserInfo login(String username, String secret) throws IOException {
      throw new UnsupportedOperationException();
    }
  }
}
