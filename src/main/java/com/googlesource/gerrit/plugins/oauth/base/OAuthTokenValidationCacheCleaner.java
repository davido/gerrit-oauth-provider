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

import com.google.gerrit.entities.Account;
import com.google.gerrit.server.account.externalids.ExternalIds;
import com.google.gerrit.server.auth.oauth.OAuthTokenRevokedListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On core OAuth-token revocation, invalidates the revoked account's Git-over-HTTP validation-cache
 * entries. The cache is token-hash keyed, so the account is mapped to its external ids ({@link
 * ExternalIds#byAccount}) and matching entries are dropped; {@link #onAllTokensRevoked} flushes
 * all.
 */
@Singleton
public class OAuthTokenValidationCacheCleaner implements OAuthTokenRevokedListener {
  private static final Logger log = LoggerFactory.getLogger(OAuthTokenValidationCacheCleaner.class);

  private final OAuthTokenValidationCache validationCache;
  private final ExternalIds externalIds;

  @Inject
  OAuthTokenValidationCacheCleaner(
      OAuthTokenValidationCache validationCache, ExternalIds externalIds) {
    this.validationCache = validationCache;
    this.externalIds = externalIds;
  }

  @Override
  public void onTokenRevoked(Account.Id accountId) {
    Set<String> accountExternalIds;
    try {
      accountExternalIds =
          externalIds.byAccount(accountId).stream()
              .map(id -> id.key().get())
              .collect(Collectors.toSet());
    } catch (IOException e) {
      // Cannot map the account to its external ids, so we cannot target its entries. A revoked
      // token must not keep authenticating a cached Git-over-HTTP hit until TTL; flush the whole
      // cache instead (over-invalidation is a brief, acceptable revalidation cost).
      log.warn(
          "Cannot resolve external ids for account {}; flushing all {} entries instead",
          accountId,
          OAuthTokenValidationCache.CACHE_NAME,
          e);
      validationCache.invalidateAll();
      return;
    }
    if (accountExternalIds.isEmpty()) {
      // No external ids resolved (unexpected for an OAuth account). We cannot target this account's
      // entries, so flush all rather than let a revoked token survive until TTL, same as the
      // lookup-failure path above.
      log.warn(
          "No external ids for account {}; flushing all {} entries instead",
          accountId,
          OAuthTokenValidationCache.CACHE_NAME);
      validationCache.invalidateAll();
      return;
    }
    log.info(
        "OAuth token revoked for account {}; invalidating its {} entries",
        accountId,
        OAuthTokenValidationCache.CACHE_NAME);
    validationCache.invalidateForExternalIds(accountExternalIds);
  }

  @Override
  public void onAllTokensRevoked() {
    // Bulk revocation: blast-radius semantics, so drop the whole cache rather than scan per
    // account.
    log.info(
        "bulk OAuth token revocation; flushing all {} entries",
        OAuthTokenValidationCache.CACHE_NAME);
    validationCache.invalidateAll();
  }
}
