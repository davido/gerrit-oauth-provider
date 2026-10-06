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

package com.googlesource.gerrit.plugins.oauth;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.oauth.base.StandardIdTokenOAuthService;
import java.util.Base64;
import org.junit.Test;

public class StandardIdTokenOAuthServiceTest {

  /** Minimal concrete subclass that keeps the default {@code decodeIdToken} (no override). */
  private static class TestIdTokenService extends StandardIdTokenOAuthService {
    TestIdTokenService() {
      super("test");
    }

    @Override
    protected OAuthUserInfo parseClaims(JsonObject claims) {
      return new OAuthUserInfo("test:" + claims.get("sub").getAsString(), null, null, null, null);
    }
  }

  @Test
  public void getUserInfo_defaultHook_decodesPayloadWithoutValidation() throws Exception {
    // The default hook decodes an unsigned/unverified JWT's claims without validating them.
    String jwt = unsignedJwt("{\"sub\":\"alice\"}");
    OAuthToken token = new OAuthToken("access", "bearer", idTokenResponse(jwt));

    OAuthUserInfo info = new TestIdTokenService().getUserInfo(token);

    assertThat(info.getExternalId()).isEqualTo("test:alice");
  }

  private static String unsignedJwt(String payloadJson) {
    Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
    String header = enc.encodeToString("{\"alg\":\"none\"}".getBytes(UTF_8));
    String payload = enc.encodeToString(payloadJson.getBytes(UTF_8));
    return header + "." + payload + ".not-a-real-signature";
  }

  private static String idTokenResponse(String jwt) {
    JsonObject raw = new JsonObject();
    raw.addProperty("id_token", jwt);
    return raw.toString();
  }
}
