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

package com.googlesource.gerrit.plugins.oauth.keycloak;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class KeycloakApiTest {
  @Test
  public void getIssuer_derivesRealmIssuer() {
    KeycloakApi a = new KeycloakApi("https://id.example.com", "gerrit");
    assertThat(a.getIssuer()).isEqualTo("https://id.example.com/realms/gerrit");
  }

  @Test
  public void getJwksEndpoint_derivesRealmCertsUrl() {
    KeycloakApi a = new KeycloakApi("https://id.example.com", "gerrit");
    assertThat(a.getJwksEndpoint())
        .isEqualTo("https://id.example.com/realms/gerrit/protocol/openid-connect/certs");
  }
}
