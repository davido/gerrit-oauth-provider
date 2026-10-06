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

package com.googlesource.gerrit.plugins.oauth.cas;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.config.PluginConfig;
import com.google.inject.ProvisionException;
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
public class CasOAuthServiceTest {
  private static final String ROOT_URL = "https://cas.example.com";

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private HttpOAuthClientFactory mockClientFactory;
  @Mock private OAuthClient mockClient;

  @Before
  public void setUp() {
    when(mockConfigFactory.create(CasOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL)).thenReturn(ROOT_URL);
    when(mockClientFactory.create(anyString(), any(OAuthProviderEndpoints.class)))
        .thenReturn(mockClient);
  }

  @Test
  public void constructor_buildsCasDescriptor_formTokenByDefault() {
    new CasOAuthService(mockConfigFactory, mockClientFactory);

    OAuthProviderEndpoints ep = capturedEndpoints();
    assertThat(ep.authorizationEndpoint()).isEqualTo(ROOT_URL + "/oauth2.0/authorize");
    assertThat(ep.tokenEndpoint()).isEqualTo(ROOT_URL + "/oauth2.0/accessToken");
    assertThat(ep.scope()).isNull();
    assertThat(ep.clientAuthStyle()).isEqualTo(ClientAuthStyle.BASIC);
    assertThat(ep.bearerPlacement()).isEqualTo(BearerPlacement.URI_QUERY_ACCESS_TOKEN);
    assertThat(ep.tokenResponseFormat()).isEqualTo(TokenResponseFormat.FORM_URL_ENCODED);
    assertThat(ep.tolerateMissingTokenType()).isTrue();
    assertThat(ep.enablePkce()).isFalse();
  }

  @Test
  public void constructor_enablePkce_readsFromConfig() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    new CasOAuthService(mockConfigFactory, mockClientFactory);

    assertThat(capturedEndpoints().enablePkce()).isTrue();
  }

  @Test
  public void constructor_useJsonExtractor_selectsJsonTokenFormat() {
    when(mockPluginConfig.getBoolean("use-json-extractor", false)).thenReturn(true);

    new CasOAuthService(mockConfigFactory, mockClientFactory);

    assertThat(capturedEndpoints().tokenResponseFormat()).isEqualTo(TokenResponseFormat.JSON);
  }

  @Test
  public void constructor_rejectsRelativeRootUrl() {
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL)).thenReturn("not-absolute");

    assertThrows(
        ProvisionException.class, () -> new CasOAuthService(mockConfigFactory, mockClientFactory));
  }

  private OAuthProviderEndpoints capturedEndpoints() {
    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockClientFactory).create(eq(CasOAuthService.PROVIDER_NAME), captor.capture());
    return captor.getValue();
  }
}
