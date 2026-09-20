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

package com.googlesource.gerrit.plugins.oauth.base;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.hash.Hashing;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.cache.CacheModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * In-memory cache of successful opaque-token validations, keyed by SHA-256 of the token. An entry
 * expires at the sooner of the token's own {@code exp} and the {@value #CACHE_NAME} hard TTL.
 */
@Singleton
public class OAuthTokenValidationCache {
  public static final String CACHE_NAME = "oauth_token_validation";
  private static final long DEFAULT_MAX_ENTRIES = 10_000L;
  private static final Duration DEFAULT_HARD_TTL = Duration.ofSeconds(60);

  public static Module module() {
    return new CacheModule() {
      @Override
      protected void configure() {
        cache(CACHE_NAME, String.class, Entry.class)
            .maximumWeight(DEFAULT_MAX_ENTRIES)
            .expireAfterWrite(DEFAULT_HARD_TTL);
      }
    };
  }

  private final Cache<String, Entry> cache;
  private final Clock clock;

  @Inject
  OAuthTokenValidationCache(@Named(CACHE_NAME) Cache<String, Entry> cache) {
    this(cache, Clock.systemUTC());
  }

  @VisibleForTesting
  public OAuthTokenValidationCache(Cache<String, Entry> cache, Clock clock) {
    this.cache = cache;
    this.clock = clock;
  }

  /**
   * Returns the cached result, evicting and missing when the token's own {@code exp} has passed.
   */
  public Optional<OAuthUserInfo> get(String bearerToken) {
    String key = hash(bearerToken);
    Entry entry = cache.getIfPresent(key);
    if (entry == null) {
      return Optional.empty();
    }
    if (entry.tokenExpiresAtMillis <= clock.millis()) {
      cache.invalidate(key);
      return Optional.empty();
    }
    return Optional.of(entry.userInfo);
  }

  /**
   * Caches the validation result. {@code tokenExpiresAtMillis} is the token's own expiry (epoch
   * millis, from the IdP); entries are never served past it, regardless of the hard TTL.
   */
  public void put(String bearerToken, OAuthUserInfo userInfo, long tokenExpiresAtMillis) {
    cache.put(hash(bearerToken), new Entry(userInfo, tokenExpiresAtMillis));
  }

  private static String hash(String token) {
    return Hashing.sha256().hashString(token, StandardCharsets.UTF_8).toString();
  }

  static final class Entry {
    final OAuthUserInfo userInfo;
    final long tokenExpiresAtMillis;

    Entry(OAuthUserInfo userInfo, long tokenExpiresAtMillis) {
      this.userInfo = userInfo;
      this.tokenExpiresAtMillis = tokenExpiresAtMillis;
    }
  }
}
