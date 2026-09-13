// Copyright (C) 2016 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.cas;

import static com.google.gerrit.json.OutputFormat.JSON;
import static com.googlesource.gerrit.plugins.oauth.utils.JsonUtil.isNull;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonArray;
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
@OAuthServiceProviderConfig(name = CasOAuthService.PROVIDER_NAME)
public class CasOAuthService extends StandardResourceOAuthService {
  public static final String PROVIDER_NAME = "cas";
  private static final String PROTECTED_RESOURCE_URL = "%s/oauth2.0/profile";
  private static final String USE_JSON_EXTRACTOR = "use-json-extractor";

  private final String rootUrl;
  private final boolean fixLegacyUserId;
  private final String extIdScheme;

  @Inject
  CasOAuthService(OAuthPluginConfigFactory cfgFactory, HttpOAuthClientFactory clientFactory) {
    super("Generic CAS OAuth2");
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    rootUrl = OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL));
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
    fixLegacyUserId = cfg.getBoolean(OAuthConfigKeys.FIX_LEGACY_USER_ID, false);
    boolean useJsonExtractor = cfg.getBoolean(USE_JSON_EXTRACTOR, false);
    boolean enablePkce = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    // Descriptor: default HTTP Basic client auth, bearer as an access_token query parameter, no
    // scope. The token response is form-encoded
    // by default (CAS's classic extractor) or JSON when use-json-extractor is set. CAS may omit
    // token_type, so tolerate it (the empty string is stored instead of failing).
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            String.format("%s/oauth2.0/authorize", rootUrl),
            String.format("%s/oauth2.0/accessToken", rootUrl),
            /* scope= */ null,
            ClientAuthStyle.BASIC,
            BearerPlacement.URI_QUERY_ACCESS_TOKEN,
            useJsonExtractor ? TokenResponseFormat.JSON : TokenResponseFormat.FORM_URL_ENCODED,
            /* tolerateMissingTokenType= */ true,
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
    if (!userJson.isJsonObject()) {
      throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
    }
    JsonObject jsonObject = userJson.getAsJsonObject();

    JsonElement id = jsonObject.get("id");
    if (isNull(id)) {
      throw new IOException(String.format("CAS response missing id: %s", body));
    }

    JsonElement attrListJson = jsonObject.get("attributes");
    if (attrListJson == null) {
      throw new IOException(String.format("CAS response missing attributes: %s", body));
    }

    String email = null, name = null, login = null;
    if (attrListJson.isJsonArray()) {
      // It is possible for CAS to be configured to not return any attributes (email, name,
      // login),
      // in which case,
      // CAS returns an empty JSON object "attributes":{}, rather than "null" or an empty JSON
      // array
      // "attributes": []

      JsonArray attrJson = attrListJson.getAsJsonArray();
      for (JsonElement elem : attrJson) {
        if (elem == null || !elem.isJsonObject()) {
          throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", elem));
        }
        JsonObject obj = elem.getAsJsonObject();

        String property = getStringElement(obj, "email");
        if (property != null) {
          email = property;
        }
        property = getStringElement(obj, "name");
        if (property != null) {
          name = property;
        }
        property = getStringElement(obj, "login");
        if (property != null) {
          login = property;
        }
      }
    }

    return new OAuthUserInfo(
        extIdScheme + ":" + id.getAsString(),
        login,
        email,
        name,
        fixLegacyUserId ? id.getAsString() : null);
  }

  private String getStringElement(JsonObject o, String name) {
    JsonElement elem = o.get(name);
    if (isNull(elem)) {
      return null;
    }

    return elem.getAsString();
  }
}
