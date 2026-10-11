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

import static com.google.common.truth.Truth.assertThat;
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
import java.net.URI;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GitLabOAuthServiceTest {
  private static final String ROOT_URL = "https://gitlab.example.com";
  private static final String EXT_ID_SCHEME =
      OAuthServiceProviderExternalIdScheme.create(GitLabOAuthService.PROVIDER_NAME);

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private HttpOAuthClientFactory mockServiceFactory;
  @Mock private OAuthClient mockClient;

  @Before
  public void setUp() {
    when(mockConfigFactory.create(GitLabOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL)).thenReturn(ROOT_URL);
    when(mockServiceFactory.create(anyString(), any(OAuthProviderEndpoints.class)))
        .thenReturn(mockClient);
  }

  private GitLabOAuthService service() {
    return new GitLabOAuthService(mockConfigFactory, mockServiceFactory);
  }

  @Test
  public void constructor_buildsGitLabDescriptor() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    service();

    OAuthProviderEndpoints ep = capturedEndpoints();
    assertThat(ep.authorizationEndpoint()).isEqualTo(ROOT_URL + "/oauth/authorize");
    assertThat(ep.tokenEndpoint()).isEqualTo(ROOT_URL + "/oauth/token");
    assertThat(ep.scope()).isNull();
    assertThat(ep.clientAuthStyle()).isEqualTo(ClientAuthStyle.REQUEST_BODY);
    assertThat(ep.bearerPlacement()).isEqualTo(BearerPlacement.AUTHORIZATION_HEADER);
    assertThat(ep.tokenResponseFormat()).isEqualTo(TokenResponseFormat.JSON);
    assertThat(ep.tolerateMissingTokenType()).isFalse();
    assertThat(ep.enablePkce()).isTrue();
  }

  @Test
  public void constructor_pkceDisabledByDefault() {
    service();

    assertThat(capturedEndpoints().enablePkce()).isFalse();
  }

  @Test
  public void refreshDisabledByDefault() {
    assertThat(service().supportsRefresh()).isFalse();
  }

  @Test
  public void refreshEnabled_supportsRefresh() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_TOKEN_REFRESH, false)).thenReturn(true);

    assertThat(service().supportsRefresh()).isTrue();
  }

  @Test
  public void refresh_delegatesToClient() throws Exception {
    OAuthToken expired = new OAuthToken("a", "s", "{}", 1L, "gitlab-oauth:gitlab");
    OAuthToken refreshed = new OAuthToken("b", "s", "{}", Long.MAX_VALUE, "gitlab-oauth:gitlab");
    when(mockClient.refresh(expired)).thenReturn(refreshed);

    assertThat(service().refresh(expired)).isEqualTo(refreshed);
    verify(mockClient).refresh(expired);
  }

  private OAuthProviderEndpoints capturedEndpoints() {
    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockServiceFactory).create(eq(GitLabOAuthService.PROVIDER_NAME), captor.capture());
    return captor.getValue();
  }

  @Test
  public void getUserInfo_fetchesApiV4UserEndpoint() throws Exception {
    when(mockClient.get(any(URI.class), any(OAuthToken.class)))
        .thenReturn("{\"id\":42,\"username\":\"tanuki\"}");

    service().getUserInfo(new OAuthToken("token", "bearer", "raw"));

    ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
    verify(mockClient).get(uri.capture(), any(OAuthToken.class));
    assertThat(uri.getValue()).isEqualTo(URI.create(ROOT_URL + "/api/v4/user"));
  }

  @Test
  public void getUserInfo_mapsUserFields() throws Exception {
    when(mockClient.get(any(URI.class), any(OAuthToken.class)))
        .thenReturn(
            "{\"id\":42,\"username\":\"tanuki\",\"email\":\"t@gitlab.com\",\"name\":\"The"
                + " Tanuki\"}");

    OAuthUserInfo info = service().getUserInfo(new OAuthToken("token", "bearer", "raw"));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_SCHEME + ":42");
    assertThat(info.getUserName()).isEqualTo("tanuki");
    assertThat(info.getEmailAddress()).isEqualTo("t@gitlab.com");
    assertThat(info.getDisplayName()).isEqualTo("The Tanuki");
  }
}
