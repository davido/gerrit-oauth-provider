// Copyright (C) 2019 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.github;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.common.base.Strings;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.server.config.PluginConfig;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GithubApiUrlTest {
  private static final String PLUGIN_NAME = "gerrit-oauth-provider";
  private static final String CANONICAL_URL = "https://localhost/";
  private static final String TEST_CLIENT_ID = "test_client_id";

  @Mock OAuthPluginConfigFactory oauthPluginConfigFactoryMock;

  private OAuthServiceProvider getGithubOAuthProvider(String rootUrl) {
    String configSection = PLUGIN_NAME + "-" + GitHubOAuthService.PROVIDER_NAME + "-oauth";
    PluginConfig.Update pluginConfig = PluginConfig.Update.forTest(configSection, new Config());
    if (!Strings.isNullOrEmpty(rootUrl)) {
      pluginConfig.setString(OAuthConfigKeys.ROOT_URL, rootUrl);
    }
    pluginConfig.setString(OAuthConfigKeys.CLIENT_ID, TEST_CLIENT_ID);
    pluginConfig.setString(OAuthConfigKeys.CLIENT_SECRET, "secret");
    when(oauthPluginConfigFactoryMock.create(GitHubOAuthService.PROVIDER_NAME))
        .thenReturn(pluginConfig.asPluginConfig());

    HttpOAuthClientFactory serviceFactory =
        new HttpOAuthClientFactory(oauthPluginConfigFactoryMock, CANONICAL_URL, PLUGIN_NAME);
    return new GitHubOAuthService(
        oauthPluginConfigFactoryMock,
        serviceFactory,
        new GitHubCheckTokenClient(oauthPluginConfigFactoryMock));
  }

  private String getExpectedUrl(String rootUrl) throws Exception {
    if (rootUrl == null) {
      rootUrl = GitHubOAuthService.GITHUB_ROOT_URL;
    }
    rootUrl = OAuthUrls.trimTrailingSlashes(rootUrl);
    return String.format(
        "%s/login/oauth/authorize?response_type=code&client_id=%s&redirect_uri=%s%s&scope=%s",
        rootUrl,
        TEST_CLIENT_ID,
        URLEncoder.encode(CANONICAL_URL, StandardCharsets.UTF_8.name()),
        URLEncoder.encode("oauth", StandardCharsets.UTF_8.name()),
        URLEncoder.encode(GitHubOAuthService.SCOPE, StandardCharsets.UTF_8.name()));
  }

  @Test
  public void nullUrlIsLoaded() throws Exception {
    String rootUrl = null;
    OAuthServiceProvider provider = getGithubOAuthProvider(rootUrl);
    assertThat(provider.getAuthorizationUrl()).isEqualTo(getExpectedUrl(rootUrl));
  }

  @Test
  public void githubUrlIsLoaded() throws Exception {
    String rootUrl = "https://github.com";
    OAuthServiceProvider provider = getGithubOAuthProvider(rootUrl);
    assertThat(provider.getAuthorizationUrl()).isEqualTo(getExpectedUrl(rootUrl));
  }

  @Test
  public void githubUrlWithTrailingSlashIsLoaded() throws Exception {
    String rootUrl = "https://github.com/";
    OAuthServiceProvider provider = getGithubOAuthProvider(rootUrl);
    assertThat(provider.getAuthorizationUrl()).isEqualTo(getExpectedUrl(rootUrl));
  }

  @Test
  public void gheUrlIsLoaded() throws Exception {
    String rootUrl = "https://git.yourcompany.com";
    OAuthServiceProvider provider = getGithubOAuthProvider(rootUrl);
    assertThat(provider.getAuthorizationUrl()).isEqualTo(getExpectedUrl(rootUrl));
  }

  @Test
  public void gheUrlWithTrailingSlashIsLoaded() throws Exception {
    String rootUrl = "https://git.yourcompany.com/";
    OAuthServiceProvider provider = getGithubOAuthProvider(rootUrl);
    assertThat(provider.getAuthorizationUrl()).isEqualTo(getExpectedUrl(rootUrl));
  }
}
