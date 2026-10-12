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

package com.googlesource.gerrit.plugins.oauth.client;

import static org.junit.Assert.assertThrows;

import java.io.IOException;
import org.junit.Test;

public class OAuthHttpTransportTest {
  @Test
  public void https_allowed() throws Exception {
    OAuthHttpTransport.requireSecure("https://gitlab.example.com/oauth/token/info");
  }

  @Test
  public void httpLoopback_allowed() throws Exception {
    OAuthHttpTransport.requireSecure("http://localhost:8080/oauth/token/info");
    OAuthHttpTransport.requireSecure("http://127.0.0.1/oauth/token/info");
  }

  @Test
  public void httpRemote_rejected() {
    assertThrows(
        IOException.class,
        () -> OAuthHttpTransport.requireSecure("http://gitlab.example.com/oauth/token/info"));
  }
}
