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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.json.OutputFormat;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.text.ParseException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Validates a compact JWT against an IdP's JWKS and its issuer/audience/expiry claims. One instance
 * per provider, built via {@link Builder}; all failures surface as {@link IOException}. Rejects
 * {@code HS*} and {@code none} to avoid algorithm-confusion attacks.
 */
public final class OidcJwtValidator {

  public static final ImmutableSet<JWSAlgorithm> DEFAULT_ALLOWED_ALGORITHMS =
      ImmutableSet.of(
          JWSAlgorithm.RS256,
          JWSAlgorithm.RS384,
          JWSAlgorithm.RS512,
          JWSAlgorithm.ES256,
          JWSAlgorithm.ES384,
          JWSAlgorithm.ES512,
          JWSAlgorithm.EdDSA);

  public static Builder builder() {
    return new Builder();
  }

  private final ImmutableSet<String> acceptedIssuers;
  private final ConfigurableJWTProcessor<SecurityContext> processor;

  private OidcJwtValidator(
      ImmutableSet<String> acceptedIssuers, ConfigurableJWTProcessor<SecurityContext> processor) {
    this.acceptedIssuers = acceptedIssuers;
    this.processor = processor;
  }

  /** Validates the compact JWT, returning its verified claims; throws on any failure. */
  public ValidatedClaims validate(String compactJwt) throws IOException {
    JWTClaimsSet claims;
    try {
      claims = processor.process(compactJwt, null);
    } catch (ParseException e) {
      throw new IOException("Malformed JWT", e);
    } catch (BadJOSEException e) {
      throw new IOException("JWT validation failed: " + e.getMessage(), e);
    } catch (JOSEException e) {
      throw new IOException("JWT signature verification failed: " + e.getMessage(), e);
    }

    String iss = claims.getIssuer();
    if (iss == null || !acceptedIssuers.contains(iss)) {
      throw new IOException("JWT issuer not accepted: " + iss);
    }

    JsonObject payload = OutputFormat.JSON.newGson().fromJson(claims.toString(), JsonObject.class);
    Set<String> aud =
        claims.getAudience() == null
            ? ImmutableSet.of()
            : ImmutableSet.copyOf(claims.getAudience());
    return new ValidatedClaims(claims.getSubject(), aud, iss, payload);
  }

  /** Builder for {@link OidcJwtValidator}. */
  public static final class Builder {
    private String jwksUri;
    private String audience;
    private final Set<String> issuers = new LinkedHashSet<>();
    private ImmutableSet<JWSAlgorithm> allowedAlgorithms = DEFAULT_ALLOWED_ALGORITHMS;
    private JWKSource<SecurityContext> jwksOverride;

    private Builder() {}

    public Builder jwksUri(String uri) {
      this.jwksUri = uri;
      return this;
    }

    public Builder audience(String aud) {
      this.audience = aud;
      return this;
    }

    /** Add one accepted issuer. May be called multiple times to add several. */
    public Builder issuer(String iss) {
      issuers.add(requireNonNull(iss));
      return this;
    }

    /** Replace the algorithm allowlist. Defaults to {@link #DEFAULT_ALLOWED_ALGORITHMS}. */
    public Builder allowedAlgorithms(Set<JWSAlgorithm> algs) {
      this.allowedAlgorithms = ImmutableSet.copyOf(algs);
      return this;
    }

    /** Test seam: inject an in-memory {@link JWKSource} instead of resolving {@link #jwksUri}. */
    @VisibleForTesting
    public Builder jwkSource(JWKSource<SecurityContext> source) {
      this.jwksOverride = source;
      return this;
    }

    public OidcJwtValidator build() {
      checkArgument(!issuers.isEmpty(), "at least one issuer is required");
      requireNonNull(audience, "audience is required");
      checkArgument(!allowedAlgorithms.isEmpty(), "algorithm allowlist must not be empty");

      JWKSource<SecurityContext> jwks;
      if (jwksOverride != null) {
        jwks = jwksOverride;
      } else {
        requireNonNull(jwksUri, "jwksUri is required");
        checkArgument(
            OAuthUrls.isSecureOrLoopback(jwksUri),
            "jwks_uri must be https (or loopback): %s",
            jwksUri);
        jwks = JwksCache.create(jwksUri);
      }

      JWSVerificationKeySelector<SecurityContext> keySelector =
          new JWSVerificationKeySelector<>(allowedAlgorithms, jwks);

      ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
      processor.setJWSKeySelector(keySelector);
      // Nimbus verifies aud/exp/nbf; issuer is checked in validate() to allow a set of issuers.
      processor.setJWTClaimsSetVerifier(
          new DefaultJWTClaimsVerifier<>(
              ImmutableSet.of(audience),
              new JWTClaimsSet.Builder().build(),
              Set.of("exp"),
              ImmutableSet.of()));

      return new OidcJwtValidator(ImmutableSet.copyOf(issuers), processor);
    }
  }
}
