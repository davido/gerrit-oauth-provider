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

package com.googlesource.gerrit.plugins.oauth.azure;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonObject;
import com.google.inject.ProvisionException;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCache;
import java.io.IOException;
import java.util.Base64;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AzureTokenValidatorTest {
  private static final String CLIENT_ID = "gerrit-app";
  private static final String TENANT_GUID = "11111111-1111-1111-1111-111111111111";

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private OAuthTokenValidationCache mockCache;

  @Before
  public void setUp() {
    when(mockConfigFactory.create(AzureActiveDirectoryService.PROVIDER_NAME))
        .thenReturn(mockPluginConfig);
    lenient().when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_ID)).thenReturn(CLIENT_ID);
    // DEFAULT_TENANT ("organizations") is a multi-tenant alias, so tid is not checked by default.
    lenient()
        .when(
            mockPluginConfig.getString(
                OAuthConfigKeys.TENANT, AzureActiveDirectoryService.DEFAULT_TENANT))
        .thenReturn(AzureActiveDirectoryService.DEFAULT_TENANT);
    lenient().when(mockCache.get(anyString())).thenReturn(Optional.empty());
  }

  @Test
  public void validate_validToken_mapsAndCaches() throws Exception {
    JsonObject payload = payload();
    payload.addProperty("appid", CLIENT_ID);
    payload.addProperty("exp", 9999999999L);
    String token = jwt(payload);

    OAuthUserInfo userInfo = validator(token, validMe()).validate(token);

    assertThat(userInfo.getExternalId()).isEqualTo("azure-oauth:az-123");
    assertThat(userInfo.getEmailAddress()).isEqualTo("jane@example.com");
    assertThat(userInfo.getDisplayName()).isEqualTo("Jane Doe");
    verify(mockCache).put(eq(token), eq(userInfo), eq(9999999999L * 1000L));
  }

  @Test
  public void validate_azpMatchesClientId_maps() throws Exception {
    JsonObject payload = payload();
    payload.addProperty("azp", CLIENT_ID);
    payload.addProperty("exp", 9999999999L);
    String token = jwt(payload);

    OAuthUserInfo userInfo = validator(token, validMe()).validate(token);

    assertThat(userInfo.getExternalId()).isEqualTo("azure-oauth:az-123");
  }

  @Test
  public void validate_foreignAppid_rejectsBeforeGraph() throws Exception {
    JsonObject payload = payload();
    payload.addProperty("appid", "foreign-app");
    String token = jwt(payload);

    assertThrows(IOException.class, () -> validatorGraphMustNotBeCalled(token).validate(token));
  }

  @Test
  public void validate_appidMatchesButAzpForeign_rejects() throws Exception {
    // The P1 OR-bypass: appid names us but the token was authorized to another party.
    JsonObject payload = payload();
    payload.addProperty("appid", CLIENT_ID);
    payload.addProperty("azp", "foreign-app");
    String token = jwt(payload);

    assertThrows(IOException.class, () -> validatorGraphMustNotBeCalled(token).validate(token));
  }

  @Test
  public void validate_noClientAppClaim_rejects() throws Exception {
    JsonObject payload = payload();
    payload.addProperty("exp", 9999999999L);
    String token = jwt(payload);

    assertThrows(IOException.class, () -> validatorGraphMustNotBeCalled(token).validate(token));
  }

  @Test
  public void validate_opaqueToken_rejectsBeforeGraph() throws Exception {
    assertThrows(
        IOException.class,
        () -> validatorGraphMustNotBeCalled("opaque-token").validate("opaque-token"));
  }

  @Test
  public void validate_graphRejectsToken_throws() throws Exception {
    JsonObject payload = payload();
    payload.addProperty("appid", CLIENT_ID);
    String token = jwt(payload);

    AzureTokenValidator validator =
        new AzureTokenValidator(mockConfigFactory, mockCache) {
          @Override
          JsonObject callGraphMe(String bearerToken) throws IOException {
            throw new IOException("Microsoft Graph rejected token: HTTP 401");
          }
        };

    assertThrows(IOException.class, () -> validator.validate(token));
  }

  @Test
  public void validate_graphResponseMissingId_rejects() throws Exception {
    JsonObject payload = payload();
    payload.addProperty("appid", CLIENT_ID);
    String token = jwt(payload);
    JsonObject me = new JsonObject();
    me.addProperty("mail", "jane@example.com");

    assertThrows(IOException.class, () -> validator(token, me).validate(token));
  }

  @Test
  public void validate_fixedTenant_tidMismatch_rejects() throws Exception {
    fixedTenant();
    JsonObject payload = payload();
    payload.addProperty("appid", CLIENT_ID);
    payload.addProperty("tid", "22222222-2222-2222-2222-222222222222");
    String token = jwt(payload);

    assertThrows(IOException.class, () -> validatorGraphMustNotBeCalled(token).validate(token));
  }

  @Test
  public void validate_fixedTenant_tidMatch_maps() throws Exception {
    fixedTenant();
    JsonObject payload = payload();
    payload.addProperty("appid", CLIENT_ID);
    payload.addProperty("tid", TENANT_GUID);
    payload.addProperty("exp", 9999999999L);
    String token = jwt(payload);

    OAuthUserInfo userInfo = validator(token, validMe()).validate(token);

    assertThat(userInfo.getExternalId()).isEqualTo("azure-oauth:az-123");
  }

  @Test
  public void validate_noExp_authenticatesButDoesNotCache() throws Exception {
    JsonObject payload = payload();
    payload.addProperty("appid", CLIENT_ID);
    String token = jwt(payload);

    OAuthUserInfo userInfo = validator(token, validMe()).validate(token);

    assertThat(userInfo.getExternalId()).isEqualTo("azure-oauth:az-123");
    verify(mockCache, never()).put(anyString(), any(), anyLong());
  }

  @Test
  public void validate_cacheHit_skipsGraph() throws Exception {
    OAuthUserInfo cached = new OAuthUserInfo("azure-oauth:az-123", null, "j@e", null, null);
    when(mockCache.get("cached-token")).thenReturn(Optional.of(cached));

    assertThat(validatorGraphMustNotBeCalled("cached-token").validate("cached-token"))
        .isSameInstanceAs(cached);
  }

  @Test
  public void constructor_blankClientId_throwsProvisionException() {
    // A blank client-id can never match a token's appid/azp, so fail fast at startup rather than
    // rejecting every Git request at validation time.
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_ID)).thenReturn("");

    assertThrows(
        ProvisionException.class, () -> new AzureTokenValidator(mockConfigFactory, mockCache));
  }

  private void fixedTenant() {
    when(mockPluginConfig.getString(
            OAuthConfigKeys.TENANT, AzureActiveDirectoryService.DEFAULT_TENANT))
        .thenReturn(TENANT_GUID);
  }

  private AzureTokenValidator validator(String expectedToken, JsonObject cannedMe) {
    return new AzureTokenValidator(mockConfigFactory, mockCache) {
      @Override
      JsonObject callGraphMe(String bearerToken) {
        assertThat(bearerToken).isEqualTo(expectedToken);
        return cannedMe;
      }
    };
  }

  private AzureTokenValidator validatorGraphMustNotBeCalled(String token) {
    return new AzureTokenValidator(mockConfigFactory, mockCache) {
      @Override
      JsonObject callGraphMe(String bearerToken) {
        throw new AssertionError("Graph /me must not be called");
      }
    };
  }

  private static JsonObject payload() {
    return new JsonObject();
  }

  private static JsonObject validMe() {
    JsonObject me = new JsonObject();
    me.addProperty("id", "az-123");
    me.addProperty("mail", "jane@example.com");
    me.addProperty("displayName", "Jane Doe");
    return me;
  }

  /** Builds a base64url JWT ({@code header.payload.signature}); the signature is never verified. */
  private static String jwt(JsonObject payload) {
    String encoded =
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(UTF_8));
    return "hdr." + encoded + ".sig";
  }
}
