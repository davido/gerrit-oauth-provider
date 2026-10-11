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

package com.googlesource.gerrit.plugins.oauth.facebook;

import static com.google.gerrit.json.OutputFormat.JSON;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.asString;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Singleton
@OAuthServiceProviderConfig(name = FacebookOAuthService.PROVIDER_NAME)
public class FacebookOAuthService extends StandardResourceOAuthService {
  private static final String AUTHORIZATION_URL = "https://www.facebook.com/dialog/oauth";
  private static final String ACCESS_TOKEN_URL = "https://graph.facebook.com/oauth/access_token";
  private static final String PROTECTED_RESOURCE_URL = "https://graph.facebook.com/me";
  public static final String PROVIDER_NAME = "facebook";
  private static final String SCOPE = "email";
  private static final String FIELDS_QUERY = "fields";
  private static final String FIELDS = "email,name";
  private final String extIdScheme;

  @Inject
  FacebookOAuthService(OAuthPluginConfigFactory cfgFactory, HttpOAuthClientFactory clientFactory) {
    super("Facebook OAuth2");
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    boolean enablePkce = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    // Descriptor: request-body client auth, JSON token response, header bearer, scope email.
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            AUTHORIZATION_URL,
            ACCESS_TOKEN_URL,
            SCOPE,
            ClientAuthStyle.REQUEST_BODY,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            /* tolerateMissingTokenType= */ false,
            enablePkce);
    client = clientFactory.create(PROVIDER_NAME, endpoints);
    extIdScheme = OAuthServiceProviderExternalIdScheme.create(PROVIDER_NAME);
  }

  @Override
  protected String resourceUrl() {
    // Percent-encode the fields value (comma -> %2C) so it is a valid single query parameter.
    return PROTECTED_RESOURCE_URL
        + "?"
        + FIELDS_QUERY
        + "="
        + URLEncoder.encode(FIELDS, StandardCharsets.UTF_8);
  }

  @Override
  protected OAuthUserInfo parseUserInfo(String body) throws IOException {
    JsonElement userJson = JSON.newGson().fromJson(body, JsonElement.class);
    if (userJson.isJsonObject()) {
      JsonObject jsonObject = userJson.getAsJsonObject();
      JsonElement id = jsonObject.get("id");
      if (isNull(id)) {
        throw new IOException("Response doesn't contain id field");
      }
      JsonElement email = jsonObject.get("email");
      JsonElement name = jsonObject.get("name");
      // Heads up!
      // Lets keep `login` equal to `email`, since `username` field is
      // deprecated for Facebook API versions v2.0 and higher
      JsonElement login = jsonObject.get("email");

      return new OAuthUserInfo(
          extIdScheme + ":" + id.getAsString(),
          asString(login),
          asString(email),
          asString(name),
          null);
    }

    throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
  }
}
