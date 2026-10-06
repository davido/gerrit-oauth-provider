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

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.MalformedURLException;
import java.net.URL;

/** Builds the JWKS source for {@link OidcJwtValidator}, rate-limiting refetches on unknown kid. */
final class JwksCache {
  private static final long COOLDOWN_MILLIS = 60_000L;

  private JwksCache() {}

  static JWKSource<SecurityContext> create(String jwksUri) {
    URL url;
    try {
      url = new URL(jwksUri);
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException("Invalid JWKS URI: " + jwksUri, e);
    }
    return JWKSourceBuilder.create(url).rateLimited(COOLDOWN_MILLIS).retrying(true).build();
  }
}
