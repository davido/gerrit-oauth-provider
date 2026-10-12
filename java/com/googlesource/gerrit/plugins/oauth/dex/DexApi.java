// Copyright (C) 2017 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.dex;

/** Dex OAuth endpoint URLs, plus the issuer and JWKS used for id_token validation. */
public class DexApi {

  private static final String AUTHORIZE_URL = "%s/dex/auth";

  private final String rootUrl;

  public DexApi(String rootUrl) {
    this.rootUrl = rootUrl;
  }

  public String getAuthorizationBaseUrl() {
    return String.format(AUTHORIZE_URL, rootUrl);
  }

  public String getAccessTokenEndpoint() {
    return String.format("%s/dex/token", rootUrl);
  }

  /** Dex's issuer: the root URL plus the {@code /dex} path its endpoints are mounted under. */
  public String getIssuer() {
    return rootUrl + "/dex";
  }

  /** JWKS endpoint: Dex serves signing keys at {@code <issuer>/keys}. */
  public String getJwksEndpoint() {
    return getIssuer() + "/keys";
  }
}
