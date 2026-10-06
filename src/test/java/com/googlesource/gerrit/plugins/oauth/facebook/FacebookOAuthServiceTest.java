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

package com.googlesource.gerrit.plugins.oauth.facebook;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.config.PluginConfig;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.client.BearerPlacement;
import com.googlesource.gerrit.plugins.oauth.client.ClientAuthStyle;
import com.googlesource.gerrit.plugins.oauth.client.OAuthClient;
import com.googlesource.gerrit.plugins.oauth.client.OAuthProviderEndpoints;
import com.googlesource.gerrit.plugins.oauth.client.TokenResponseFormat;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class FacebookOAuthServiceTest {
  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private HttpOAuthClientFactory mockClientFactory;
  @Mock private OAuthClient mockClient;

  @Before
  public void setUp() {
    when(mockConfigFactory.create(FacebookOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockClientFactory.create(anyString(), any(OAuthProviderEndpoints.class)))
        .thenReturn(mockClient);
  }

  @Test
  public void constructor_buildsFacebookDescriptor() {
    new FacebookOAuthService(mockConfigFactory, mockClientFactory);

    OAuthProviderEndpoints ep = capturedEndpoints();
    assertThat(ep.authorizationEndpoint()).isEqualTo("https://www.facebook.com/dialog/oauth");
    assertThat(ep.tokenEndpoint()).isEqualTo("https://graph.facebook.com/oauth/access_token");
    assertThat(ep.scope()).isEqualTo("email");
    assertThat(ep.clientAuthStyle()).isEqualTo(ClientAuthStyle.REQUEST_BODY);
    assertThat(ep.bearerPlacement()).isEqualTo(BearerPlacement.AUTHORIZATION_HEADER);
    assertThat(ep.tokenResponseFormat()).isEqualTo(TokenResponseFormat.JSON);
    assertThat(ep.tolerateMissingTokenType()).isFalse();
    assertThat(ep.enablePkce()).isFalse();
  }

  @Test
  public void constructor_enablePkce_readsFromConfig() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    new FacebookOAuthService(mockConfigFactory, mockClientFactory);

    assertThat(capturedEndpoints().enablePkce()).isTrue();
  }

  private OAuthProviderEndpoints capturedEndpoints() {
    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockClientFactory).create(eq(FacebookOAuthService.PROVIDER_NAME), captor.capture());
    return captor.getValue();
  }
}
