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
import static org.mockito.Mockito.when;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.account.externalids.ExternalIds;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCache.Entry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class OAuthTokenValidationCacheCleanerTest {
  private static final Account.Id ACCOUNT = Account.id(1);
  private static final String TOKEN_ALICE = "token-alice";
  private static final String TOKEN_BOB = "token-bob";
  private static final OAuthUserInfo ALICE =
      new OAuthUserInfo("keycloak-oauth:alice", "alice", "alice@example.com", "Alice", null);
  private static final OAuthUserInfo BOB =
      new OAuthUserInfo("keycloak-oauth:bob", "bob", "bob@example.com", "Bob", null);

  @Mock private ExternalIds externalIds;

  private OAuthTokenValidationCache validationCache;
  private OAuthTokenValidationCacheCleaner cleaner;

  @Before
  public void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
    Cache<String, Entry> backing = CacheBuilder.newBuilder().build();
    validationCache = new OAuthTokenValidationCache(backing, clock);
    cleaner = new OAuthTokenValidationCacheCleaner(validationCache, externalIds);
    long expiresAt = clock.millis() + 60_000;
    validationCache.put(TOKEN_ALICE, ALICE, expiresAt);
    validationCache.put(TOKEN_BOB, BOB, expiresAt);
  }

  private static ExternalId externalId(String scheme, String id) {
    return ExternalId.create(ExternalId.Key.create(scheme, id, false), ACCOUNT, null, null);
  }

  @Test
  public void onTokenRevoked_targeted_dropsOnlyThatIdentity() throws Exception {
    when(externalIds.byAccount(ACCOUNT))
        .thenReturn(ImmutableSet.of(externalId("keycloak-oauth", "alice")));

    cleaner.onTokenRevoked(ACCOUNT);

    assertThat(validationCache.get(TOKEN_ALICE)).isEmpty(); // targeted identity dropped
    assertThat(validationCache.get(TOKEN_BOB)).hasValue(BOB); // other identity untouched
  }

  @Test
  public void onTokenRevoked_lookupFails_flushesAll() throws Exception {
    when(externalIds.byAccount(ACCOUNT)).thenThrow(new IOException("boom"));

    cleaner.onTokenRevoked(ACCOUNT);

    assertThat(validationCache.get(TOKEN_ALICE)).isEmpty();
    assertThat(validationCache.get(TOKEN_BOB)).isEmpty();
  }

  @Test
  public void onTokenRevoked_noExternalIds_flushesAll() throws Exception {
    when(externalIds.byAccount(ACCOUNT)).thenReturn(ImmutableSet.of());

    cleaner.onTokenRevoked(ACCOUNT);

    assertThat(validationCache.get(TOKEN_ALICE)).isEmpty();
    assertThat(validationCache.get(TOKEN_BOB)).isEmpty();
  }

  @Test
  public void onAllTokensRevoked_flushesAll() {
    cleaner.onAllTokensRevoked();

    assertThat(validationCache.get(TOKEN_ALICE)).isEmpty();
    assertThat(validationCache.get(TOKEN_BOB)).isEmpty();
  }
}
