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

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.Test;

/** Smoke tests for {@link JwksCache} construction. */
public class JwksCacheTest {

  @Test
  public void create_withValidHttpsUri_returnsSource() {
    JWKSource<SecurityContext> source =
        JwksCache.create("https://idp.example.com/realms/main/protocol/openid-connect/certs");
    assertThat(source).isNotNull();
  }

  @Test
  public void create_withMalformedUri_throwsIllegalArgumentException() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> JwksCache.create("not://a valid uri"));
    assertThat(ex).hasMessageThat().contains("Invalid JWKS URI");
  }
}
