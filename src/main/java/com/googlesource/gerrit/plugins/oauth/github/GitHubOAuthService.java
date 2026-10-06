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

import static com.google.gerrit.json.OutputFormat.JSON;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonElement;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderConfig;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.googlesource.gerrit.plugins.oauth.base.StandardResourceOAuthService;
import com.googlesource.gerrit.plugins.oauth.client.BearerPlacement;
import com.googlesource.gerrit.plugins.oauth.client.ClientAuthStyle;
import com.googlesource.gerrit.plugins.oauth.client.OAuthProviderEndpoints;
import com.googlesource.gerrit.plugins.oauth.client.TokenResponseFormat;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import java.io.IOException;

@Singleton
@OAuthServiceProviderConfig(name = GitHubOAuthService.PROVIDER_NAME)
public class GitHubOAuthService extends StandardResourceOAuthService {
  public static final String PROVIDER_NAME = "github";
  static final String GITHUB_ROOT_URL = "https://github.com";
  static final String SCOPE = "user:email";

  private final GitHubCheckTokenClient checker;
  private final GitHub2Api api;
  private final String extIdScheme;
  private final GitHubUserInfoMapper userInfoMapper;

  @Inject
  GitHubOAuthService(
      OAuthPluginConfigFactory cfgFactory,
      HttpOAuthClientFactory clientFactory,
      GitHubCheckTokenClient checker) {
    super("GitHub OAuth2");
    this.checker = checker;
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    boolean fixLegacyUserId = cfg.getBoolean(OAuthConfigKeys.FIX_LEGACY_USER_ID, false);
    String rootUrl =
        OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL, GITHUB_ROOT_URL));
    api = new GitHub2Api(rootUrl);
    boolean enablePkce = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    // GitHub's browser flow: form-encoded token response, default HTTP Basic client auth,
    // Authorization-header bearer. GitHub2Api still supplies the endpoint URLs (and the REST API
    // URL for the check-token Git path).
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            api.getAuthorizationBaseUrl(),
            api.getAccessTokenEndpoint(),
            SCOPE,
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.FORM_URL_ENCODED,
            /* tolerateMissingTokenType= */ false,
            enablePkce);
    client = clientFactory.create(PROVIDER_NAME, endpoints);
    extIdScheme = OAuthServiceProviderExternalIdScheme.create(PROVIDER_NAME);
    userInfoMapper = new GitHubUserInfoMapper(extIdScheme, fixLegacyUserId);
  }

  @Override
  protected String resourceUrl() {
    return api.getApiUrl() + "/user";
  }

  /** Validates the token at check-token (app binding) and returns the subject to bind to /user. */
  @Override
  protected String verifyToken(OAuthToken token) throws IOException {
    return checker.validate(token.getToken()).userInfo.getExternalId();
  }

  @Override
  @Nullable
  protected String resourceSubject(String body) throws IOException {
    JsonElement userJson = JSON.newGson().fromJson(body, JsonElement.class);
    if (userJson != null && userJson.isJsonObject()) {
      JsonElement id = userJson.getAsJsonObject().get("id");
      if (!isNull(id)) {
        return extIdScheme + ":" + id.getAsString();
      }
    }
    return null;
  }

  @Override
  protected OAuthUserInfo parseUserInfo(String body) throws IOException {
    JsonElement userJson = JSON.newGson().fromJson(body, JsonElement.class);
    if (!userJson.isJsonObject()) {
      throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
    }
    return userInfoMapper.map(userJson.getAsJsonObject());
  }
}
