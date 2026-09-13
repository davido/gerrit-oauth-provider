// Copyright (C) 2015 The Android Open Source Project
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

/** Google OAuth endpoint URLs. Also feeds the Git-over-HTTP tokeninfo path. */
public class Google2Api {
  public String getAccessTokenEndpoint() {
    return "https://www.googleapis.com/oauth2/v4/token";
  }

  public String getAuthorizationBaseUrl() {
    return "https://accounts.google.com/o/oauth2/auth";
  }

  /** Endpoint that introspects an opaque Google {@code access_token}. */
  public String getTokenInfoEndpoint() {
    return "https://oauth2.googleapis.com/tokeninfo";
  }

  /** OAuth 2.0 token revocation endpoint (RFC 7009); revoking a refresh token kills the grant. */
  public String getRevocationEndpoint() {
    return "https://oauth2.googleapis.com/revoke";
  }
}
