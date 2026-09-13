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

/**
 * Where the client puts the bearer token when fetching a protected resource. Changing a provider's
 * placement would break its resource fetch, so this must match the provider's behavior exactly.
 */
public enum BearerPlacement {
  /** {@code Authorization: Bearer <token>} header. The normal case. */
  AUTHORIZATION_HEADER,
  /**
   * {@code ?access_token=<token>} query parameter. Used by the providers whose APIs read the token
   * from the query string: Dex, Bitbucket, AirVantage, Phabricator, and CAS.
   */
  URI_QUERY_ACCESS_TOKEN
}
