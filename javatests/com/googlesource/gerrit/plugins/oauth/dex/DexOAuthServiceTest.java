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

package com.googlesource.gerrit.plugins.oauth.dex;

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
public class DexOAuthServiceTest {
  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private OAuthClient mockClient;
  @Mock private HttpOAuthClientFactory mockServiceFactory;

  private static final String CLIENT_ID = "gerrit-client";
  private static final String ROOT_URL = "https://dex.example.com";
  private static final String ISSUER = ROOT_URL + "/dex";
  private static final String EXT_ID_PREFIX = "dex-oauth:";
  private static final String EMAIL = "jane.doe@example.com";

  private static RSAKey rsaKey;
  private static RSAKey otherKey;
  private static JWKSource<SecurityContext> jwks;

  @BeforeClass
  public static void generateKeys() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("primary").generate();
    otherKey = new RSAKeyGenerator(2048).keyID("attacker").generate();
    jwks = new ImmutableJWKSet<>(new JWKSet(List.of(rsaKey.toPublicJWK())));
  }

  @Before
  public void setUp() {
    when(mockConfigFactory.create(DexOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.SERVICE_NAME, "Dex OAuth2"))
        .thenReturn("Dex OAuth2");
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL)).thenReturn(ROOT_URL);
    when(mockServiceFactory.create(anyString(), any(OAuthProviderEndpoints.class)))
        .thenReturn(mockClient);
  }

  private DexOAuthService serviceWithValidator() {
    return new DexOAuthService(mockConfigFactory, mockServiceFactory, testValidator());
  }

  @Test
  public void constructor_buildsDexDescriptor() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    serviceWithValidator();

    OAuthProviderEndpoints ep = capturedEndpoints();
    assertThat(ep.authorizationEndpoint()).isEqualTo(ROOT_URL + "/dex/auth");
    assertThat(ep.tokenEndpoint()).isEqualTo(ROOT_URL + "/dex/token");
    assertThat(ep.scope()).isEqualTo("openid profile email offline_access");
    assertThat(ep.clientAuthStyle()).isEqualTo(ClientAuthStyle.BASIC);
    assertThat(ep.bearerPlacement()).isEqualTo(BearerPlacement.URI_QUERY_ACCESS_TOKEN);
    assertThat(ep.tokenResponseFormat()).isEqualTo(TokenResponseFormat.JSON);
    assertThat(ep.tolerateMissingTokenType()).isFalse();
    assertThat(ep.enablePkce()).isTrue();
  }

  private OAuthProviderEndpoints capturedEndpoints() {
    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockServiceFactory).create(eq(DexOAuthService.PROVIDER_NAME), captor.capture());
    return captor.getValue();
  }

  @Test
  public void getUserInfo_validSignedIdToken_mapsUser() throws Exception {
    OAuthUserInfo info =
        serviceWithValidator().getUserInfo(idTokenResponse(sign(claims().build())));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_PREFIX + EMAIL);
    assertThat(info.getEmailAddress()).isEqualTo(EMAIL);
    assertThat(info.getDisplayName()).isEqualTo("Jane Doe");
  }

  @Test
  public void getUserInfo_badSignature_rejected() throws Exception {
    // Signed by a key absent from the JWKS -> signature verification fails.
    String jwt = signWith(otherKey, claims().build());

    assertThrows(IOException.class, () -> serviceWithValidator().getUserInfo(idTokenResponse(jwt)));
  }

  @Test
  public void getUserInfo_noValidator_rejected() throws Exception {
    // No client-id -> no validator -> the id_token must be refused, not trusted unsigned.
    DexOAuthService service = new DexOAuthService(mockConfigFactory, mockServiceFactory, null);
    assertThrows(
        IOException.class, () -> service.getUserInfo(idTokenResponse(sign(claims().build()))));
  }

  @Test
  public void getUserInfo_wrongAudience_rejected() throws Exception {
    String jwt = sign(claims().audience("some-other-client").build());

    assertThrows(IOException.class, () -> serviceWithValidator().getUserInfo(idTokenResponse(jwt)));
  }

  private static OidcJwtValidator testValidator() {
    return OidcJwtValidator.builder().issuer(ISSUER).audience(CLIENT_ID).jwkSource(jwks).build();
  }

  private static JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .subject("dex-sub-123")
        .issuer(ISSUER)
        .audience(CLIENT_ID)
        .claim("email", EMAIL)
        .claim("name", "Jane Doe")
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plusSeconds(300)));
  }

  private static String sign(JWTClaimsSet claimSet) throws Exception {
    return signWith(rsaKey, claimSet);
  }

  private static String signWith(RSAKey key, JWTClaimsSet claimSet) throws Exception {
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(key.getKeyID())
                .build(),
            claimSet);
    signed.sign(new RSASSASigner(key));
    return signed.serialize();
  }

  private static OAuthToken idTokenResponse(String jwt) {
    JsonObject raw = new JsonObject();
    raw.addProperty("id_token", jwt);
    return new OAuthToken("access", "Bearer", raw.toString());
  }
}
