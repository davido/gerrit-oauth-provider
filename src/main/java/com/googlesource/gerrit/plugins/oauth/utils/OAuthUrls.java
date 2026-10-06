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

import com.google.common.base.CharMatcher;
import java.net.URI;
import java.util.Locale;

/** Helpers for normalizing provider root URLs. */
public final class OAuthUrls {
  private OAuthUrls() {}

  /** Removes any trailing slashes; returns {@code null} unchanged. */
  public static String trimTrailingSlashes(String url) {
    return url == null ? null : CharMatcher.is('/').trimTrailingFrom(url);
  }

  /**
   * Returns {@code true} if the URL is {@code https} or targets a loopback host -- i.e. safe to
   * send a token to, or fetch signing keys from, without a MITM leaking or forging credentials.
   */
  public static boolean isSecureOrLoopback(String url) {
    URI uri = URI.create(url);
    String host = uri.getHost();
    if ("https".equalsIgnoreCase(uri.getScheme())) {
      // Reject opaque/hostless https (e.g. "https:gitlab.example.com"): nothing to secure a
      // connection to, and getHost() would be null.
      return host != null && !host.isEmpty();
    }
    if (host == null) {
      return false;
    }
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
  }
}
