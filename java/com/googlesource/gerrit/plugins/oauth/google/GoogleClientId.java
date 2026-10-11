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

package com.googlesource.gerrit.plugins.oauth.google;

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.common.Nullable;

/** Helpers for Google OAuth client-ids. */
final class GoogleClientId {
  private static final String SUFFIX = ".apps.googleusercontent.com";

  private GoogleClientId() {}

  /** Normalizes a client-id to the full audience form Google returns in {@code aud}/{@code azp}. */
  @Nullable
  static String normalizeAudience(@Nullable String clientId) {
    if (clientId == null || clientId.isBlank()) {
      return null;
    }
    return clientId.endsWith(SUFFIX) ? clientId : clientId + SUFFIX;
  }

  /**
   * The set of token audiences this Gerrit accepts: the browser {@code client-id} plus any {@code
   * trusted-audience} entries (e.g. a Desktop client used by git credential helpers). Each is
   * normalized to the full {@code .apps.googleusercontent.com} form.
   */
  static ImmutableSet<String> trustedAudiences(@Nullable String clientId, String[] configured) {
    ImmutableSet.Builder<String> audiences = ImmutableSet.builder();
    String primary = normalizeAudience(clientId);
    if (primary != null) {
      audiences.add(primary);
    }
    if (configured != null) {
      for (String audience : configured) {
        String normalized = normalizeAudience(audience);
        if (normalized != null) {
          audiences.add(normalized);
        }
      }
    }
    return audiences.build();
  }
}
