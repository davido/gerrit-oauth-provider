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

import org.junit.Test;

public class OAuthUrlsTest {
  @Test
  public void trimTrailingSlashes() {
    assertThat(OAuthUrls.trimTrailingSlashes("https://idp.example.com"))
        .isEqualTo("https://idp.example.com");
    assertThat(OAuthUrls.trimTrailingSlashes("https://idp.example.com/"))
        .isEqualTo("https://idp.example.com");
    assertThat(OAuthUrls.trimTrailingSlashes("https://idp.example.com///"))
        .isEqualTo("https://idp.example.com");
    assertThat(OAuthUrls.trimTrailingSlashes("https://idp.example.com/a/b/"))
        .isEqualTo("https://idp.example.com/a/b");
  }

  @Test
  public void isSecureOrLoopback_https() {
    assertThat(OAuthUrls.isSecureOrLoopback("https://gitlab.example.com/oauth/token/info"))
        .isTrue();
    assertThat(OAuthUrls.isSecureOrLoopback("https://gitlab.example.com")).isTrue();
  }

  @Test
  public void isSecureOrLoopback_httpRemote_rejected() {
    assertThat(OAuthUrls.isSecureOrLoopback("http://gitlab.example.com/oauth/token/info"))
        .isFalse();
  }

  @Test
  public void isSecureOrLoopback_httpLoopback_allowed() {
    assertThat(OAuthUrls.isSecureOrLoopback("http://localhost:8080/x")).isTrue();
    assertThat(OAuthUrls.isSecureOrLoopback("http://127.0.0.1/x")).isTrue();
    assertThat(OAuthUrls.isSecureOrLoopback("http://[::1]:8080/x")).isTrue();
  }

  @Test
  public void isSecureOrLoopback_loopbackCaseInsensitive() {
    assertThat(OAuthUrls.isSecureOrLoopback("http://LOCALHOST/x")).isTrue();
  }

  @Test
  public void isSecureOrLoopback_userinfoLoopbackSpoof_rejected() {
    // 127.0.0.1 is userinfo here; the real host is evil.example.
    assertThat(OAuthUrls.isSecureOrLoopback("http://127.0.0.1@evil.example/x")).isFalse();
  }

  @Test
  public void isSecureOrLoopback_opaqueHttps_rejected() {
    // Hostless/opaque https must not count as secure.
    assertThat(OAuthUrls.isSecureOrLoopback("https:gitlab.example.com")).isFalse();
  }
}
