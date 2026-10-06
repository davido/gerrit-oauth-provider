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

package com.googlesource.gerrit.plugins.oauth.sap;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthAuthorizationInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.server.config.PluginConfig;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.client.BearerPlacement;
import com.googlesource.gerrit.plugins.oauth.client.ClientAuthStyle;
import com.googlesource.gerrit.plugins.oauth.client.OAuthClient;
import com.googlesource.gerrit.plugins.oauth.client.OAuthProviderEndpoints;
import com.googlesource.gerrit.plugins.oauth.client.TokenResponseFormat;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.validation.CombiningValidator;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SAPIasOAuthServiceTest {

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private HttpOAuthClientFactory mockClientFactory;
  @Mock private OAuthClient mockClient;
  @Mock private CombiningValidator<Token> mockTokenValidator;

  private static final String TEST_SAP_ROOT_URL = "https://accounts.sap.com";
  private static final String DEFAULT_SERVICE_NAME = "SAP IAS";

  @Before
  public void setUp() {
    when(mockConfigFactory.create(SAPIasOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL)).thenReturn(TEST_SAP_ROOT_URL);
    when(mockPluginConfig.getString(OAuthConfigKeys.SERVICE_NAME, DEFAULT_SERVICE_NAME))
        .thenReturn(DEFAULT_SERVICE_NAME);
    when(mockClientFactory.create(anyString(), any(OAuthProviderEndpoints.class)))
        .thenReturn(mockClient);
  }

  private SAPIasOAuthService newService() {
    return new SAPIasOAuthService(mockConfigFactory, mockClientFactory, mockTokenValidator);
  }

  private OAuthProviderEndpoints capturedEndpoints() {
    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockClientFactory).create(eq(SAPIasOAuthService.PROVIDER_NAME), captor.capture());
    return captor.getValue();
  }

  @Test
  public void constructor_buildsSapDescriptor() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    newService();

    OAuthProviderEndpoints ep = capturedEndpoints();
    assertThat(ep.authorizationEndpoint()).isEqualTo(TEST_SAP_ROOT_URL + "/oauth2/authorize");
    assertThat(ep.tokenEndpoint()).isEqualTo(TEST_SAP_ROOT_URL + "/oauth2/token");
    assertThat(ep.scope()).isEqualTo("openid profile email");
    assertThat(ep.clientAuthStyle()).isEqualTo(ClientAuthStyle.BASIC);
    assertThat(ep.bearerPlacement()).isEqualTo(BearerPlacement.AUTHORIZATION_HEADER);
    assertThat(ep.tokenResponseFormat()).isEqualTo(TokenResponseFormat.JSON);
    assertThat(ep.tolerateMissingTokenType()).isTrue();
    assertThat(ep.enablePkce()).isTrue();
  }

  @Test
  public void getAuthorizationInfo_withPkce_shouldDelegateToClient() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    OAuthAuthorizationInfo expected =
        new OAuthAuthorizationInfo(
            "https://sap.com/auth?code_challenge=xyz", "sap-secret-verifier");
    when(mockClient.getAuthorizationInfo()).thenReturn(expected);

    SAPIasOAuthService service = newService();
    OAuthAuthorizationInfo info = service.getAuthorizationInfo();

    assertThat(info.getPkceVerifier()).isEqualTo("sap-secret-verifier");
    assertThat(capturedEndpoints().enablePkce()).isTrue();
  }

  @Test
  public void getAccessToken_withPkce_shouldDelegateVerifierToClient() throws Exception {
    SAPIasOAuthService service = newService();

    OAuthVerifier verifier = new OAuthVerifier("auth-code");
    String verifierFromSession = "session-stored-verifier";
    OAuthToken expected = new OAuthToken("token", "Bearer", "dummy-raw");
    when(mockClient.exchangeCode(verifier, verifierFromSession)).thenReturn(expected);

    OAuthToken result = service.getAccessToken(verifier, verifierFromSession);

    assertThat(result).isSameInstanceAs(expected);
    verify(mockClient).exchangeCode(verifier, verifierFromSession);
  }

  @Test
  public void getAccessToken_passwordGrant_shouldDelegateToClient() throws Exception {
    SAPIasOAuthService service = newService();

    OAuthToken expected = new OAuthToken("token", "Bearer", "raw");
    when(mockClient.passwordGrant("jane@example.com", "secret")).thenReturn(expected);

    OAuthToken result = service.getAccessToken("jane@example.com", "secret");

    assertThat(result).isSameInstanceAs(expected);
    verify(mockClient).passwordGrant("jane@example.com", "secret");
  }
}
