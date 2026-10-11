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
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
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
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KeycloakTokenValidatorTest {
  private static final String CLIENT_ID = "gerrit-client";
  private static final String ISSUER = "https://id.example.com/realms/gerrit";

  private static RSAKey rsaKey;
  private static JWKSource<SecurityContext> jwks;

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;

  @BeforeClass
  public static void generateKey() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("primary").generate();
    jwks = new ImmutableJWKSet<>(new JWKSet(List.of(rsaKey.toPublicJWK())));
  }

  @Before
  public void setUp() {
    when(mockConfigFactory.create(KeycloakOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_ID)).thenReturn(CLIENT_ID);
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.USE_PREFERRED_USERNAME, true))
        .thenReturn(true);
  }

  @Test
  public void validate_validAccessToken_mapsUserInfo() throws Exception {
    String jwt = sign(rsaKey, claims().build());

    OAuthUserInfo userInfo = validator().validate(jwt);

    assertThat(userInfo.getUserName()).isEqualTo("alice");
    assertThat(userInfo.getExternalId()).isEqualTo("keycloak-oauth:alice");
    assertThat(userInfo.getEmailAddress()).isEqualTo("alice@example.com");
    // the Git path uses map(), not mapForBrowser(), so it never links to an existing account.
    assertThat(userInfo.getClaimedIdentity()).isNull();
  }

  @Test
  public void validate_wrongAudience_throwsIOException() throws Exception {
    String jwt = sign(rsaKey, claims().audience("some-other-client").build());

    assertThrows(IOException.class, () -> validator().validate(jwt));
  }

  @Test
  public void validate_missingPreferredUsername_throwsIOException() throws Exception {
    JWTClaimsSet noUsername =
        baseClaims().claim("email", "alice@example.com").claim("name", "Alice").build();
    String jwt = sign(rsaKey, noUsername);

    assertThrows(IOException.class, () -> validator().validate(jwt));
  }

  private KeycloakTokenValidator validator() {
    OidcJwtValidator oidc =
        OidcJwtValidator.builder().issuer(ISSUER).audience(CLIENT_ID).jwkSource(jwks).build();
    return new KeycloakTokenValidator(mockConfigFactory, oidc);
  }

  private static JWTClaimsSet.Builder claims() {
    return baseClaims()
        .claim("preferred_username", "alice")
        .claim("email", "alice@example.com")
        .claim("name", "Alice");
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
}
