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

package com.googlesource.gerrit.plugins.oauth.keycloak;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.client.BearerPlacement;
import com.googlesource.gerrit.plugins.oauth.client.ClientAuthStyle;
import com.googlesource.gerrit.plugins.oauth.client.OAuthClient;
import com.googlesource.gerrit.plugins.oauth.client.OAuthProviderEndpoints;
import com.googlesource.gerrit.plugins.oauth.client.TokenResponseFormat;
import com.googlesource.gerrit.plugins.oauth.jwt.OidcJwtValidator;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KeycloakOAuthServiceTest {
  private static final String ROOT_URL = "https://id.example.com";
  private static final String REALM = "gerrit";
  private static final String CLIENT_ID = "gerrit-client";
  private static final String ISSUER = ROOT_URL + "/realms/" + REALM;

  private static RSAKey rsaKey; // signing key whose public half is in the JWKS
  private static RSAKey rogueKey; // valid key but NOT in the JWKS
  private static JWKSource<SecurityContext> jwks;

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private OAuthClient mockClient;
  @Mock private HttpOAuthClientFactory mockServiceFactory;

  @BeforeClass
  public static void generateKeys() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("primary").generate();
    rogueKey = new RSAKeyGenerator(2048).keyID("rogue").generate();
    jwks = new ImmutableJWKSet<>(new JWKSet(List.of(rsaKey.toPublicJWK())));
  }

  @Before
  public void setUp() {
    when(mockConfigFactory.create(KeycloakOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.SERVICE_NAME, "Keycloak OAuth2"))
        .thenReturn("Keycloak OAuth2");
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL)).thenReturn(ROOT_URL);
    when(mockPluginConfig.getString(OAuthConfigKeys.REALM)).thenReturn(REALM);
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_ID)).thenReturn(CLIENT_ID);
    // Defaults; individual tests override these, hence lenient.
    lenient()
        .when(mockPluginConfig.getBoolean(OAuthConfigKeys.USE_PREFERRED_USERNAME, true))
        .thenReturn(true);
    lenient()
        .when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false))
        .thenReturn(false);
    when(mockServiceFactory.create(anyString(), any(OAuthProviderEndpoints.class)))
        .thenReturn(mockClient);
  }

  @Test
  public void getUserInfo_validSignedIdToken_returnsUserInfo() throws Exception {
    String jwt = sign(rsaKey, claims().build());

    OAuthUserInfo userInfo = service().getUserInfo(idTokenResponse(jwt));

    assertThat(userInfo.getUserName()).isEqualTo("alice");
    assertThat(userInfo.getExternalId()).isEqualTo("keycloak-oauth:alice");
    assertThat(userInfo.getEmailAddress()).isEqualTo("alice@example.com");
    assertThat(userInfo.getDisplayName()).isEqualTo("Alice Example");
  }

  @Test
  public void getUserInfo_wrongIssuer_throwsIOException() throws Exception {
    String jwt = sign(rsaKey, claims().issuer("https://attacker.example/").build());

    assertThrows(IOException.class, () -> service().getUserInfo(idTokenResponse(jwt)));
  }

  @Test
  public void getUserInfo_wrongAudience_throwsIOException() throws Exception {
    String jwt = sign(rsaKey, claims().audience("some-other-client").build());

    assertThrows(IOException.class, () -> service().getUserInfo(idTokenResponse(jwt)));
  }

  @Test
  public void getUserInfo_signatureFromUnknownKey_throwsIOException() throws Exception {
    // Correct claims but signed with a key whose public half is not published in the JWKS.
    String jwt = sign(rogueKey, claims().build());

    assertThrows(IOException.class, () -> service().getUserInfo(idTokenResponse(jwt)));
  }

  @Test
  public void getUserInfo_missingPreferredUsername_throwsIOException() throws Exception {
    // Signature/issuer/audience valid, but the claim the mapper needs is absent.
    JWTClaimsSet noUsername =
        baseClaims().claim("email", "alice@example.com").claim("name", "Alice Example").build();
    String jwt = sign(rsaKey, noUsername);

    assertThrows(IOException.class, () -> service().getUserInfo(idTokenResponse(jwt)));
  }

  @Test
  public void getUserInfo_usePreferredUsernameFalse_leavesUsernameNull() throws Exception {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.USE_PREFERRED_USERNAME, true))
        .thenReturn(false);
    String jwt = sign(rsaKey, claims().build());

    OAuthUserInfo userInfo = service().getUserInfo(idTokenResponse(jwt));

    assertThat(userInfo.getUserName()).isNull();
    assertThat(userInfo.getExternalId()).isEqualTo("keycloak-oauth:alice");
  }

  @Test
  public void constructor_buildsKeycloakDescriptor() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    service();

    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockServiceFactory).create(eq(KeycloakOAuthService.PROVIDER_NAME), captor.capture());
    OAuthProviderEndpoints ep = captor.getValue();
    assertThat(ep.authorizationEndpoint()).isEqualTo(ISSUER + "/protocol/openid-connect/auth");
    assertThat(ep.tokenEndpoint()).isEqualTo(ISSUER + "/protocol/openid-connect/token");
    assertThat(ep.scope()).isEqualTo("openid");
    assertThat(ep.clientAuthStyle()).isEqualTo(ClientAuthStyle.REQUEST_BODY);
    assertThat(ep.bearerPlacement()).isEqualTo(BearerPlacement.URI_QUERY_ACCESS_TOKEN);
    assertThat(ep.tokenResponseFormat()).isEqualTo(TokenResponseFormat.JSON);
    assertThat(ep.tolerateMissingTokenType()).isFalse();
    assertThat(ep.enablePkce()).isTrue();
  }

  @Test
  public void refreshDisabledByDefault() {
    assertThat(service().supportsRefresh()).isFalse();
  }

  @Test
  public void refreshEnabled_supportsRefresh_keepsOpenidScope() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_TOKEN_REFRESH, false)).thenReturn(true);

    KeycloakOAuthService svc = service();

    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockServiceFactory).create(eq(KeycloakOAuthService.PROVIDER_NAME), captor.capture());
    assertThat(captor.getValue().scope()).isEqualTo("openid");
    assertThat(svc.supportsRefresh()).isTrue();
  }

  @Test
  public void refresh_delegatesToClient() throws Exception {
    OAuthToken expired = new OAuthToken("a", "s", "{}", 1L, "keycloak-oauth:keycloak");
    OAuthToken refreshed =
        new OAuthToken("b", "s", "{}", Long.MAX_VALUE, "keycloak-oauth:keycloak");
    when(mockClient.refresh(expired)).thenReturn(refreshed);

    assertThat(service().refresh(expired)).isEqualTo(refreshed);
    verify(mockClient).refresh(expired);
  }

  private KeycloakOAuthService service() {
    OidcJwtValidator validator =
        OidcJwtValidator.builder().issuer(ISSUER).audience(CLIENT_ID).jwkSource(jwks).build();
    return new KeycloakOAuthService(mockConfigFactory, mockServiceFactory, validator);
  }

  private static JWTClaimsSet.Builder claims() {
    return baseClaims()
        .claim("preferred_username", "alice")
        .claim("email", "alice@example.com")
        .claim("name", "Alice Example");
  }

  private static JWTClaimsSet.Builder baseClaims() {
    return new JWTClaimsSet.Builder()
        .subject("alice")
        .issuer(ISSUER)
        .audience(CLIENT_ID)
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plusSeconds(300)));
  }

  private static String sign(RSAKey key, JWTClaimsSet claimSet) throws Exception {
    JWSSigner signer = new RSASSASigner(key);
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(key.getKeyID())
                .build(),
            claimSet);
    signed.sign(signer);
    return signed.serialize();
  }

  private static OAuthToken idTokenResponse(String jwt) {
    JsonObject raw = new JsonObject();
    raw.addProperty("id_token", jwt);
    return new OAuthToken("access-token", "bearer", raw.toString());
  }
}
