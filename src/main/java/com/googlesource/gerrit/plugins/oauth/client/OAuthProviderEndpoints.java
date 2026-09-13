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

import static java.util.Objects.requireNonNull;

import com.google.gerrit.common.Nullable;

/**
 * The static, per-provider OAuth 2.0 protocol shape the {@code HttpOAuthClient} needs. It encodes
 * only what varies by provider; the {@code client_id}/{@code client_secret}/callback stay in {@code
 * PluginConfig} and are read by the client factory, and the protected-resource URL is passed per
 * call to {@link OAuthClient#get}.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code authorizationEndpoint} / {@code tokenEndpoint} -- the provider's OAuth endpoints
 *       (absolute URLs; validated non-blank here, scheme/https enforced downstream by {@link
 *       OAuthHttpTransport}).
 *   <li>{@code scope} -- the default scope string, or {@code null} for providers that send none
 *       (e.g. GitLab's browser flow).
 *   <li>{@code clientAuthStyle} -- token-endpoint client authentication ({@link ClientAuthStyle}).
 *   <li>{@code bearerPlacement} -- where the access token goes on resource fetches ({@link
 *       BearerPlacement}); must match the provider's current behavior exactly.
 *   <li>{@code tokenResponseFormat} -- how to parse the token response ({@link
 *       TokenResponseFormat}).
 *   <li>{@code tolerateMissingTokenType} -- store a missing {@code token_type} as the empty string
 *       instead of failing (CAS).
 *   <li>{@code enablePkce} -- initialize PKCE on the authorization redirect and replay the verifier
 *       on token exchange.
 * </ul>
 */
public record OAuthProviderEndpoints(
    String authorizationEndpoint,
    String tokenEndpoint,
    @Nullable String scope,
    ClientAuthStyle clientAuthStyle,
    BearerPlacement bearerPlacement,
    TokenResponseFormat tokenResponseFormat,
    boolean tolerateMissingTokenType,
    boolean enablePkce) {
  public OAuthProviderEndpoints {
    requireNonBlank(authorizationEndpoint, "authorizationEndpoint");
    requireNonBlank(tokenEndpoint, "tokenEndpoint");
    requireNonNull(clientAuthStyle, "clientAuthStyle");
    requireNonNull(bearerPlacement, "bearerPlacement");
    requireNonNull(tokenResponseFormat, "tokenResponseFormat");
    // Normalize an empty scope to null so "omit scope" is a single condition (a null check): the
    // authorization URL and token request emit the scope parameter only when it is non-empty.
    if (scope != null && scope.isEmpty()) {
      scope = null;
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be a non-blank endpoint URL");
    }
  }
}
