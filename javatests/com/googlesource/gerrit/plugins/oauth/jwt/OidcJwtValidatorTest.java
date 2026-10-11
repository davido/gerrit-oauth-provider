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

package com.googlesource.gerrit.plugins.oauth.jwt;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.BeforeClass;
import org.junit.Test;

public class OidcJwtValidatorTest {

  private static final String ISSUER = "https://idp.example.com/realms/main";
  private static final String AUDIENCE = "gerrit-client";

  private static RSAKey rsaKey; // primary signing key, present in the JWKS
  private static RSAKey otherRsaKey; // valid key but NOT in the JWKS
  private static JWKSource<SecurityContext> jwks;

  @BeforeClass
  public static void generateKeys() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("primary").generate();
    otherRsaKey = new RSAKeyGenerator(2048).keyID("rogue").generate();
    jwks = new ImmutableJWKSet<>(new JWKSet(List.of(rsaKey.toPublicJWK())));
  }

  private OidcJwtValidator validator() {
    return OidcJwtValidator.builder().issuer(ISSUER).audience(AUDIENCE).jwkSource(jwks).build();
  }

  @Test
  public void validate_happyPath() throws Exception {
    String jwt = sign(rsaKey, JWSAlgorithm.RS256, claims().build());

    ValidatedClaims result = validator().validate(jwt);

    assertThat(result.subject()).isEqualTo("alice");
    assertThat(result.audience()).containsExactly(AUDIENCE);
    assertThat(result.issuer()).isEqualTo(ISSUER);
    assertThat(result.payload().get("email").getAsString()).isEqualTo("alice@example.com");
  }

  @Test
  public void validate_audienceAsArrayWithMatch_accepted() throws Exception {
    String jwt =
        sign(rsaKey, JWSAlgorithm.RS256, claims().audience(List.of("other", AUDIENCE)).build());

    ValidatedClaims result = validator().validate(jwt);

    assertThat(result.audience()).containsExactly("other", AUDIENCE);
  }

  @Test
  public void validate_wrongAudience_rejected() throws Exception {
    String jwt = sign(rsaKey, JWSAlgorithm.RS256, claims().audience("some-other-client").build());

    IOException ex = assertThrows(IOException.class, () -> validator().validate(jwt));
    assertThat(ex).hasMessageThat().contains("validation failed");
  }

  @Test
  public void validate_missingAudience_rejected() throws Exception {
    // A missing aud claim must be rejected the same as a mismatch when an audience is configured.
    JWTClaimsSet noAud =
        new JWTClaimsSet.Builder()
            .subject("alice")
            .issuer(ISSUER)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("email", "alice@example.com")
            .build();
    String jwt = sign(rsaKey, JWSAlgorithm.RS256, noAud);

    IOException ex = assertThrows(IOException.class, () -> validator().validate(jwt));
    assertThat(ex).hasMessageThat().contains("validation failed");
  }

  @Test
  public void validate_wrongIssuer_rejected() throws Exception {
    String jwt =
        sign(rsaKey, JWSAlgorithm.RS256, claims().issuer("https://attacker.example/").build());

    IOException ex = assertThrows(IOException.class, () -> validator().validate(jwt));
    assertThat(ex).hasMessageThat().contains("issuer not accepted");
  }

  @Test
  public void validate_multipleAcceptedIssuers_eachAccepted() throws Exception {
    String altIssuer = "accounts.example.com"; // mimics Google's dual-issuer wart
    OidcJwtValidator v =
        OidcJwtValidator.builder()
            .issuer(ISSUER)
            .issuer(altIssuer)
            .audience(AUDIENCE)
            .jwkSource(jwks)
            .build();

    String first = sign(rsaKey, JWSAlgorithm.RS256, claims().build());
    String second = sign(rsaKey, JWSAlgorithm.RS256, claims().issuer(altIssuer).build());

    assertThat(v.validate(first).issuer()).isEqualTo(ISSUER);
    assertThat(v.validate(second).issuer()).isEqualTo(altIssuer);
  }

  @Test
  public void validate_expiredToken_rejected() throws Exception {
    String jwt =
        sign(
            rsaKey,
            JWSAlgorithm.RS256,
            claims()
                .expirationTime(Date.from(Instant.now().minusSeconds(300)))
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .build());

    assertThrows(IOException.class, () -> validator().validate(jwt));
  }

  @Test
  public void validate_notYetValid_rejected() throws Exception {
    String jwt =
        sign(
            rsaKey,
            JWSAlgorithm.RS256,
            claims().notBeforeTime(Date.from(Instant.now().plusSeconds(600))).build());

    assertThrows(IOException.class, () -> validator().validate(jwt));
  }

  @Test
  public void validate_missingExp_rejected() throws Exception {
    // exp is in our required-claims set; absence must be rejected.
    String jwt = sign(rsaKey, JWSAlgorithm.RS256, claimsWithoutExp().build());

    assertThrows(IOException.class, () -> validator().validate(jwt));
  }

  @Test
  public void validate_kidNotInJwks_rejected() throws Exception {
    // Sign with a key whose public half is not in the JWKS.
    String jwt = sign(otherRsaKey, JWSAlgorithm.RS256, claims().build());

    assertThrows(IOException.class, () -> validator().validate(jwt));
  }

  @Test
  public void validate_algNone_rejected() throws Exception {
    // Construct an unsigned ("alg: none") JWT. Validator must refuse it before any key lookup.
    PlainJWT plain = new PlainJWT(new PlainHeader(), claims().build());
    String jwt = plain.serialize();

    assertThrows(IOException.class, () -> validator().validate(jwt));
  }

  @Test
  public void validate_hs256WithJwksPublicKeyAsSecret_rejected() throws Exception {
    // Algorithm-confusion attack: sign with HS256 using the JWKS public key bytes as the secret.
    // Must be rejected because HS256 is not in the allowlist.
    byte[] publicKeyBytes = rsaKey.toPublicJWK().toRSAPublicKey().getEncoded();
    // HS256 requires >= 256 bits; the RSA-encoded public key easily satisfies that.
    JWSSigner signer = new MACSigner(new SecretKeySpec(publicKeyBytes, "HmacSHA256"));
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT)
                .keyID(rsaKey.getKeyID())
                .build(),
            claims().build());
    signed.sign(signer);

    assertThrows(IOException.class, () -> validator().validate(signed.serialize()));
  }

  @Test
  public void validate_malformedJwt_rejected() throws Exception {
    assertThrows(IOException.class, () -> validator().validate("not.a.jwt"));
    assertThrows(IOException.class, () -> validator().validate("definitely-not-a-jwt"));
  }

  @Test
  public void validate_payloadExposedToCaller() throws Exception {
    String jwt =
        sign(
            rsaKey,
            JWSAlgorithm.RS256,
            claims()
                .claim("preferred_username", "alice")
                .claim("groups", List.of("admins"))
                .build());

    ValidatedClaims result = validator().validate(jwt);

    assertThat(result.payload().get("preferred_username").getAsString()).isEqualTo("alice");
    assertThat(result.payload().getAsJsonArray("groups").get(0).getAsString()).isEqualTo("admins");
  }

  @Test
  public void builder_emptyIssuerSet_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OidcJwtValidator.builder().audience(AUDIENCE).jwkSource(jwks).build());
  }

  @Test
  public void builder_missingAudience_rejected() {
    assertThrows(
        NullPointerException.class,
        () -> OidcJwtValidator.builder().issuer(ISSUER).jwkSource(jwks).build());
  }

  @Test
  public void builder_httpJwksUri_rejected() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                OidcJwtValidator.builder()
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .jwksUri("http://idp.example.com/jwks")
                    .build());
    assertThat(ex).hasMessageThat().contains("jwks_uri must be https");
  }

  @Test
  public void builder_emptyAlgorithmList_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OidcJwtValidator.builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .jwkSource(jwks)
                .allowedAlgorithms(ImmutableSet.of())
                .build());
  }

  private static JWTClaimsSet.Builder claims() {
    return claimsWithoutExp().expirationTime(Date.from(Instant.now().plusSeconds(300)));
  }

  private static JWTClaimsSet.Builder claimsWithoutExp() {
    return new JWTClaimsSet.Builder()
        .subject("alice")
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .issueTime(Date.from(Instant.now()))
        .claim("email", "alice@example.com");
  }

  private static String sign(RSAKey key, JWSAlgorithm alg, JWTClaimsSet claimSet) throws Exception {
    JWSSigner signer = new RSASSASigner(key);
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(alg).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
            claimSet);
    signed.sign(signer);
    return signed.serialize();
  }
}
