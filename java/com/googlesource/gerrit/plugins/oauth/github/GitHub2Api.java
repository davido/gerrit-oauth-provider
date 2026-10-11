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

package com.googlesource.gerrit.plugins.oauth.github;

/** GitHub OAuth endpoint URLs. Also feeds the Git-over-HTTP token-check path. */
public class GitHub2Api {
  private static final String AUTHORIZE_URL = "%s/login/oauth/authorize";
  private static final String GITHUB_API_ENDPOINT_URL = "https://api.github.com";
  private static final String GHE_API_ENDPOINT_URL = "%s/api/v3";

  private final String rootUrl;

  public GitHub2Api(String rootUrl) {
    this.rootUrl = rootUrl;
  }

  public String getAccessTokenEndpoint() {
    return String.format("%s/login/oauth/access_token", rootUrl);
  }

  /**
   * The REST API base URL: {@code https://api.github.com} for github.com, else {@code
   * <root>/api/v3}.
   */
  public String getApiUrl() {
    return GitHubOAuthService.GITHUB_ROOT_URL.equals(rootUrl)
        ? GITHUB_API_ENDPOINT_URL
        : String.format(GHE_API_ENDPOINT_URL, rootUrl);
  }

  /** The "check a token" endpoint that validates an OAuth-App access token for {@code clientId}. */
  public String getApplicationsTokenEndpoint(String clientId) {
    return getApiUrl() + "/applications/" + clientId + "/token";
  }

  public String getAuthorizationBaseUrl() {
    return String.format(AUTHORIZE_URL, rootUrl);
  }
}
