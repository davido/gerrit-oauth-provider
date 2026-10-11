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

package com.googlesource.gerrit.plugins.oauth.utils;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JsonUtilTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void jwtPayloadJson_decodesBase64UrlWithoutPadding() throws Exception {
    String payloadJson = "{\"email\":\"alice@example.com\",\"name\":\"Alice\"}";
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

    String jwt = "header." + payload + ".signature";

    assertThat(JsonUtil.jwtPayloadJson(jwt)).isEqualTo(payloadJson);
  }

  @Test
  public void jwtPayloadJson_invalidBase64Url_throwsIOException() throws Exception {
    thrown.expect(IOException.class);
    thrown.expectMessage("Invalid JWT payload encoding");

    JsonUtil.jwtPayloadJson("header.in!valid.signature");
  }

  @Test
  public void jwtPayloadJson_malformedJwtStructure_propagatesRuntimeException() throws Exception {
    thrown.expect(IllegalStateException.class);

    JsonUtil.jwtPayloadJson("not-a-jwt");
  }

  @Test
  public void jwtPayloadJson_emptyPayload_propagatesRuntimeException() throws Exception {
    thrown.expect(IllegalStateException.class);

    // header..signature - payload is empty, but still 3 parts
    JsonUtil.jwtPayloadJson("header..signature");
  }
}
