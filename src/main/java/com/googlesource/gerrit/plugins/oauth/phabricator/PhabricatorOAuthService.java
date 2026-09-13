// Copyright (C) 2020 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.phabricator;

import static com.google.gerrit.json.OutputFormat.JSON;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.asString;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
@OAuthServiceProviderConfig(name = PhabricatorOAuthService.PROVIDER_NAME)
public class PhabricatorOAuthService extends StandardResourceOAuthService {
  public static final String PROVIDER_NAME = "phabricator";
  private static final String PROTECTED_RESOURCE_URL = "%s/api/user.whoami";
  private final String rootUrl;
  private final String extIdScheme;

  @Inject
  PhabricatorOAuthService(
      OAuthPluginConfigFactory cfgFactory, HttpOAuthClientFactory clientFactory) {
    super("Phabricator OAuth2");
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    rootUrl = OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL));
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
    boolean enablePkce = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    // Descriptor: default HTTP Basic client auth, JSON token response, no scope, and the
    // bearer as an access_token query parameter.
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            String.format("%s/oauthserver/auth/", rootUrl),
            String.format("%s/oauthserver/token/", rootUrl),
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
    return String.format(PROTECTED_RESOURCE_URL, rootUrl);
  }

  @Override
  protected OAuthUserInfo parseUserInfo(String body) throws IOException {
    JsonElement userJson = JSON.newGson().fromJson(body, JsonElement.class);
    if (userJson.isJsonObject()) {
      JsonObject jsonObject = userJson.getAsJsonObject();
      JsonElement jsonResult = jsonObject.get("result");
      if (jsonResult == null) {
        throw new IOException("Response doesn't contain result field");
      }
      JsonObject resultObject = jsonResult.getAsJsonObject();
      JsonElement id = resultObject.get("phid");
      if (isNull(id)) {
        throw new IOException("Response doesn't contain id field");
      }
      JsonElement email = resultObject.get("primaryEmail");
      JsonElement name = resultObject.get("realName");
      JsonElement username = resultObject.get("userName");
      String login = null;

      if (!username.isJsonNull()) {
        login = username.getAsString();
      }
      return new OAuthUserInfo(
          extIdScheme + ":" + id.getAsString(), login, asString(email), asString(name), null);
    }

    throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
  }
}
