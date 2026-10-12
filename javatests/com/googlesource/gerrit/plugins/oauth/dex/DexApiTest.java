// Copyright (C) 2020 The Android Open Source Project
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

import org.junit.Test;

public class DexApiTest {
  @Test
  public void issuerAndJwks_underDexPath() {
    DexApi dex = new DexApi("https://example.com");
    // Endpoints live under /dex, so the issuer and JWKS must match that mount point.
    assertThat(dex.getAuthorizationBaseUrl()).isEqualTo("https://example.com/dex/auth");
    assertThat(dex.getAccessTokenEndpoint()).isEqualTo("https://example.com/dex/token");
    assertThat(dex.getIssuer()).isEqualTo("https://example.com/dex");
    assertThat(dex.getJwksEndpoint()).isEqualTo("https://example.com/dex/keys");
  }
}
