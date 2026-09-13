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

package com.googlesource.gerrit.plugins.oauth.bitbucket;

import static com.google.gerrit.json.OutputFormat.JSON;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.io.IOException;

@Singleton
@OAuthServiceProviderConfig(name = BitbucketOAuthService.PROVIDER_NAME)
public class BitbucketOAuthService extends StandardResourceOAuthService {
  public static final String PROVIDER_NAME = "bitbucket";
  private static final String AUTHORIZATION_URL = "https://bitbucket.org/site/oauth2/authorize";
  private static final String ACCESS_TOKEN_URL = "https://bitbucket.org/site/oauth2/access_token";
  private static final String PROTECTED_RESOURCE_URL = "https://bitbucket.org/api/1.0/user/";
  private final boolean fixLegacyUserId;
  private final String extIdScheme;

  @Inject
  BitbucketOAuthService(OAuthPluginConfigFactory cfgFactory, HttpOAuthClientFactory clientFactory) {
    super("Bitbucket OAuth2");
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    fixLegacyUserId = cfg.getBoolean(OAuthConfigKeys.FIX_LEGACY_USER_ID, false);
    boolean enablePkce = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    // Descriptor: default HTTP Basic client auth, JSON token response, no scope, and the
    // bearer as an access_token query parameter.
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            AUTHORIZATION_URL,
            ACCESS_TOKEN_URL,
            /* scope= */ null,
            ClientAuthStyle.BASIC,
            BearerPlacement.URI_QUERY_ACCESS_TOKEN,
            TokenResponseFormat.JSON,
            /* tolerateMissingTokenType= */ false,
            enablePkce);
    client = clientFactory.create(PROVIDER_NAME, endpoints);
    extIdScheme = OAuthServiceProviderExternalIdScheme.create(PROVIDER_NAME);
  }

  @Override
  protected String resourceUrl() {
    return PROTECTED_RESOURCE_URL;
  }

  @Override
  protected OAuthUserInfo parseUserInfo(String body) throws IOException {
    JsonElement userJson = JSON.newGson().fromJson(body, JsonElement.class);
    if (userJson.isJsonObject()) {
      JsonObject jsonObject = userJson.getAsJsonObject();
      JsonObject userObject = jsonObject.getAsJsonObject("user");
      if (isNull(userObject)) {
        throw new IOException("Response doesn't contain 'user' field");
      }
      JsonElement usernameElement = userObject.get("username");
      String username = usernameElement.getAsString();

      JsonElement displayName = jsonObject.get("display_name");
      return new OAuthUserInfo(
          extIdScheme + ":" + username,
          username,
          null,
          displayName == null || displayName.isJsonNull() ? null : displayName.getAsString(),
          fixLegacyUserId ? username : null);
    }

    throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
  }
}
