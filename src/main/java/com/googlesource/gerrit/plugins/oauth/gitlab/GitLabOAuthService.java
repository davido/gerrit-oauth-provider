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

package com.googlesource.gerrit.plugins.oauth.gitlab;

import static com.google.gerrit.json.OutputFormat.JSON;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonElement;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
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
import java.net.URI;

@Singleton
@OAuthServiceProviderConfig(name = GitLabOAuthService.PROVIDER_NAME)
public class GitLabOAuthService extends StandardResourceOAuthService {
  private static final String PROTECTED_RESOURCE_URL = "%s/api/v4/user";
  public static final String PROVIDER_NAME = "gitlab";
  private final String rootUrl;
  private final GitLabUserInfoMapper userInfoMapper;

  @Inject
  GitLabOAuthService(OAuthPluginConfigFactory cfgFactory, HttpOAuthClientFactory clientFactory) {
    super("GitLab OAuth2");
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    rootUrl = OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL));
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
    boolean enablePkce = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    // GitLab: request-body client auth, JSON token response, no scope, and an
    // Authorization-header bearer on the /api/v4/user resource GET. GitLabApi still supplies the
    // authorize/token URLs. The Git-over-HTTP token/info validator is unaffected.
    GitLabApi api = new GitLabApi(rootUrl);
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            api.getAuthorizationBaseUrl(),
            api.getAccessTokenEndpoint(),
            /* scope= */ null,
            ClientAuthStyle.REQUEST_BODY,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            /* tolerateMissingTokenType= */ false,
            enablePkce);
    client = clientFactory.create(PROVIDER_NAME, endpoints);
    userInfoMapper =
        new GitLabUserInfoMapper(OAuthServiceProviderExternalIdScheme.create(PROVIDER_NAME));
  }

  @Override
  protected String resourceUrl() {
    return String.format(PROTECTED_RESOURCE_URL, rootUrl);
  }

  @Override
  protected OAuthUserInfo parseUserInfo(String body) throws IOException {
    JsonElement userJson = JSON.newGson().fromJson(body, JsonElement.class);
    return userInfoMapper.map(userJson.getAsJsonObject());
  }
}
