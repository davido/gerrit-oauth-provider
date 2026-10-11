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

package com.googlesource.gerrit.plugins.oauth.github;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.googlesource.gerrit.plugins.oauth.client.BearerPlacement;
import com.googlesource.gerrit.plugins.oauth.client.ClientAuthStyle;
import com.googlesource.gerrit.plugins.oauth.client.OAuthClient;
import com.googlesource.gerrit.plugins.oauth.client.OAuthProviderEndpoints;
import com.googlesource.gerrit.plugins.oauth.client.TokenResponseFormat;
import com.googlesource.gerrit.plugins.oauth.github.GitHubCheckTokenClient.Validated;
import java.io.IOException;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GitHubOAuthServiceTest {
  private static final String EXT_ID_SCHEME =
      OAuthServiceProviderExternalIdScheme.create(GitHubOAuthService.PROVIDER_NAME);

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private HttpOAuthClientFactory mockServiceFactory;
  @Mock private OAuthClient mockClient;
  @Mock private GitHubCheckTokenClient checker;

  @Before
  public void setUp() {
    when(mockConfigFactory.create(GitHubOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL, GitHubOAuthService.GITHUB_ROOT_URL))
        .thenReturn(GitHubOAuthService.GITHUB_ROOT_URL);
    when(mockServiceFactory.create(anyString(), any(OAuthProviderEndpoints.class)))
        .thenReturn(mockClient);
  }

  @Test
  public void constructor_buildsGitHubDescriptor() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    service();

    OAuthProviderEndpoints ep = capturedEndpoints();
    assertThat(ep.authorizationEndpoint()).isEqualTo("https://github.com/login/oauth/authorize");
    assertThat(ep.tokenEndpoint()).isEqualTo("https://github.com/login/oauth/access_token");
    assertThat(ep.scope()).isEqualTo(GitHubOAuthService.SCOPE);
    assertThat(ep.clientAuthStyle()).isEqualTo(ClientAuthStyle.BASIC);
    assertThat(ep.bearerPlacement()).isEqualTo(BearerPlacement.AUTHORIZATION_HEADER);
    assertThat(ep.tokenResponseFormat()).isEqualTo(TokenResponseFormat.FORM_URL_ENCODED);
    assertThat(ep.tolerateMissingTokenType()).isFalse();
    assertThat(ep.enablePkce()).isTrue();
  }

  @Test
  public void constructor_pkceDisabledByDefault() {
    service();

    assertThat(capturedEndpoints().enablePkce()).isFalse();
  }

  private OAuthProviderEndpoints capturedEndpoints() {
    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockServiceFactory).create(eq(GitHubOAuthService.PROVIDER_NAME), captor.capture());
    return captor.getValue();
  }

  private GitHubOAuthService service() {
    return new GitHubOAuthService(mockConfigFactory, mockServiceFactory, checker);
  }

  private void checkTokenBindsUserId(String token, String id) throws Exception {
    OAuthUserInfo appBound = new OAuthUserInfo(EXT_ID_SCHEME + ":" + id, null, null, null, null);
    when(checker.validate(token)).thenReturn(new Validated(appBound, Optional.empty()));
  }

  @Test
  public void getUserInfo_populatesProfileFromUserEndpoint() throws Exception {
    checkTokenBindsUserId("browser-token", "12345");
    when(mockClient.get(any(), any(OAuthToken.class)))
        .thenReturn(
            "{\"id\":12345,\"login\":\"octocat\",\"email\":\"octo@github.com\",\"name\":\"The"
                + " Octocat\"}");

    OAuthUserInfo info = service().getUserInfo(new OAuthToken("browser-token", "bearer", "raw"));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_SCHEME + ":12345");
    assertThat(info.getUserName()).isEqualTo("octocat");
    assertThat(info.getEmailAddress()).isEqualTo("octo@github.com");
    assertThat(info.getDisplayName()).isEqualTo("The Octocat");
  }

  @Test
  public void getUserInfo_rejectsSubjectMismatchBetweenCheckTokenAndUser() throws Exception {
    checkTokenBindsUserId("browser-token", "12345");
    when(mockClient.get(any(), any(OAuthToken.class)))
        .thenReturn("{\"id\":99999,\"login\":\"someone-else\"}");

    assertThrows(
        IOException.class,
        () -> service().getUserInfo(new OAuthToken("browser-token", "bearer", "raw")));
  }
}
