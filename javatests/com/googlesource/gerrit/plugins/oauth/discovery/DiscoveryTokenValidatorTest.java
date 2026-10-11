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

package com.googlesource.gerrit.plugins.oauth.discovery;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.inject.ProvisionException;
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
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DiscoveryTokenValidatorTest {
  private static final String CLIENT_ID = "gerrit-client";
  private static final String ISSUER = "https://id.example.com/realms/gerrit";

  private static RSAKey rsaKey;
  private static JWKSource<SecurityContext> jwks;

  @Mock private DiscoveryOAuthService mockService;

  @BeforeClass
  public static void generateKey() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("primary").generate();
    jwks = new ImmutableJWKSet<>(new JWKSet(List.of(rsaKey.toPublicJWK())));
  }

  @Test
  public void constructor_noJwksValidator_throwsProvisionException() {
    when(mockService.validator()).thenReturn(null);

    assertThrows(ProvisionException.class, () -> new DiscoveryTokenValidator(mockService));
  }

  @Test
  public void validate_validAccessToken_mapsUserInfo() throws Exception {
    when(mockService.validator()).thenReturn(testValidator());
    when(mockService.userInfoMapper()).thenReturn(mapper("discovery-oauth"));
    String jwt = sign(claims().build());

    OAuthUserInfo userInfo = new DiscoveryTokenValidator(mockService).validate(jwt);

    assertThat(userInfo.getExternalId()).isEqualTo("discovery-oauth:12345");
    assertThat(userInfo.getUserName()).isEqualTo("jane.doe");
    assertThat(userInfo.getEmailAddress()).isEqualTo("jane.doe@example.com");
  }

  @Test
  public void validate_wrongAudience_throwsIOException() throws Exception {
    when(mockService.validator()).thenReturn(testValidator());
    when(mockService.userInfoMapper()).thenReturn(mapper("discovery-oauth"));
    String jwt = sign(claims().audience("some-other-client").build());

    assertThrows(IOException.class, () -> new DiscoveryTokenValidator(mockService).validate(jwt));
  }

  @Test
  public void validate_missingSub_throwsIOException() throws Exception {
    when(mockService.validator()).thenReturn(testValidator());
    when(mockService.userInfoMapper()).thenReturn(mapper("discovery-oauth"));
    // Signature/issuer/audience valid, but no sub -> the mapper has no external id to derive.
    JWTClaimsSet noSub =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(CLIENT_ID)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("preferred_username", "jane.doe")
            .claim("email", "jane.doe@example.com")
            .claim("name", "Jane Doe")
            .build();

    assertThrows(
        IOException.class, () -> new DiscoveryTokenValidator(mockService).validate(sign(noSub)));
  }

  @Test
  public void validate_configuredExternalIdScheme_gitPathEmitsIt() throws Exception {
    // The Git path must honor external-id-scheme by reusing the service mapper, matching browser
    // login -- else an Auth0 migration would map auth0-oauth on the browser but discovery-oauth
    // here.
    when(mockService.validator()).thenReturn(testValidator());
    when(mockService.userInfoMapper()).thenReturn(mapper("auth0-oauth"));

    OAuthUserInfo userInfo =
        new DiscoveryTokenValidator(mockService).validate(sign(claims().build()));

    assertThat(userInfo.getExternalId()).isEqualTo("auth0-oauth:12345");
  }

  @Test
  public void validate_gitIgnoresLinkToExisting_mapsSubWithoutUsername() throws Exception {
    when(mockService.validator()).thenReturn(testValidator());
    // Mapper built with linkToExistingGerrit=true, but the Git path uses map() (not mapForBrowser),
    // so a token with sub and no username still authenticates by external id -- no fail-closed.
    when(mockService.userInfoMapper())
        .thenReturn(
            new DiscoveryUserInfoMapper("discovery-oauth", /* linkToExistingGerrit= */ true));
    JWTClaimsSet noUsername =
        new JWTClaimsSet.Builder()
            .subject("12345")
            .issuer(ISSUER)
            .audience(CLIENT_ID)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();

    OAuthUserInfo userInfo = new DiscoveryTokenValidator(mockService).validate(sign(noUsername));

    assertThat(userInfo.getExternalId()).isEqualTo("discovery-oauth:12345");
    assertThat(userInfo.getClaimedIdentity()).isNull();
  }

  private static DiscoveryUserInfoMapper mapper(String scheme) {
    return new DiscoveryUserInfoMapper(scheme, /* linkToExistingGerrit= */ false);
  }

  private static OidcJwtValidator testValidator() {
    return OidcJwtValidator.builder().issuer(ISSUER).audience(CLIENT_ID).jwkSource(jwks).build();
  }

  private static JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .subject("12345")
        .issuer(ISSUER)
        .audience(CLIENT_ID)
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .claim("preferred_username", "jane.doe")
        .claim("email", "jane.doe@example.com")
        .claim("name", "Jane Doe");
  }

  private static String sign(JWTClaimsSet claimSet) throws Exception {
    JWSSigner signer = new RSASSASigner(rsaKey);
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(rsaKey.getKeyID())
                .build(),
            claimSet);
    signed.sign(signer);
    return signed.serialize();
  }
}
