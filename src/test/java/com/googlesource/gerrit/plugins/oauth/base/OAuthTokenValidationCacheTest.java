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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCache.Entry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class OAuthTokenValidationCacheTest {
  private static final String TOKEN_A = "token-a";
  private static final String TOKEN_B = "token-b";
  private static final OAuthUserInfo USER_INFO =
      new OAuthUserInfo("keycloak-oauth:alice", "alice", "alice@example.com", "Alice", null);

  private MutableClock clock;
  private OAuthTokenValidationCache validationCache;

  @Before
  public void setUp() {
    clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    Cache<String, Entry> backing = CacheBuilder.newBuilder().build();
    validationCache = new OAuthTokenValidationCache(backing, clock);
  }

  @Test
  public void get_emptyCache_returnsEmpty() {
    assertThat(validationCache.get(TOKEN_A)).isEmpty();
  }

  @Test
  public void put_thenGet_returnsCachedUserInfo() {
    long expiresAt = clock.millis() + 60_000;
    validationCache.put(TOKEN_A, USER_INFO, expiresAt);

    Optional<OAuthUserInfo> result = validationCache.get(TOKEN_A);

    assertThat(result).hasValue(USER_INFO);
  }

  @Test
  public void get_doesNotReturnEntryForDifferentToken() {
    validationCache.put(TOKEN_A, USER_INFO, clock.millis() + 60_000);

    assertThat(validationCache.get(TOKEN_B)).isEmpty();
  }

  @Test
  public void get_evictsAndReturnsEmptyAfterTokenExpiry() {
    long expiresAt = clock.millis() + 30_000;
    validationCache.put(TOKEN_A, USER_INFO, expiresAt);
    assertThat(validationCache.get(TOKEN_A)).hasValue(USER_INFO);

    clock.advanceMillis(31_000);

    assertThat(validationCache.get(TOKEN_A)).isEmpty();
    // Re-read confirms the entry was evicted, not just missed once.
    assertThat(validationCache.get(TOKEN_A)).isEmpty();
  }

  @Test
  public void get_returnsCachedUntilTokenExpiry() {
    long expiresAt = clock.millis() + 30_000;
    validationCache.put(TOKEN_A, USER_INFO, expiresAt);

    clock.advanceMillis(29_999);

    assertThat(validationCache.get(TOKEN_A)).hasValue(USER_INFO);
  }

  @Test
  public void put_overwritesPreviousEntry() {
    validationCache.put(TOKEN_A, USER_INFO, clock.millis() + 60_000);
    OAuthUserInfo replacement =
        new OAuthUserInfo("keycloak-oauth:bob", "bob", "bob@example.com", "Bob", null);

    validationCache.put(TOKEN_A, replacement, clock.millis() + 60_000);

    assertThat(validationCache.get(TOKEN_A)).hasValue(replacement);
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant initial) {
      this.now = initial;
    }

    void advanceMillis(long millis) {
      now = now.plusMillis(millis);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }
  }
}
