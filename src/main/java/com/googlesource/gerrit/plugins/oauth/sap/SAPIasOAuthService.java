// Copyright (C) 2025 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.sap;

import com.google.common.base.Strings;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oauth.base.AbstractOAuthService;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderConfig;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.googlesource.gerrit.plugins.oauth.client.BearerPlacement;
import com.googlesource.gerrit.plugins.oauth.client.ClientAuthStyle;
import com.googlesource.gerrit.plugins.oauth.client.OAuthProviderEndpoints;
import com.googlesource.gerrit.plugins.oauth.client.TokenResponseFormat;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import com.sap.cloud.security.json.DefaultJsonObject;
import com.sap.cloud.security.token.SapIdToken;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.validation.CombiningValidator;
import com.sap.cloud.security.token.validation.ValidationResult;
import java.io.IOException;
import java.net.URI;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
@OAuthServiceProviderConfig(name = SAPIasOAuthService.PROVIDER_NAME)
public class SAPIasOAuthService extends AbstractOAuthService {
  static final String PROVIDER_NAME = "sapias";
  private final boolean linkExistingGerrit;
  private final String extIdScheme;
  private final CombiningValidator<Token> tokenValidator;

  @Inject
  SAPIasOAuthService(
      OAuthPluginConfigFactory cfgFactory,
      HttpOAuthClientFactory clientFactory,
      CombiningValidator<Token> tokenValidator) {
    super(cfgFactory.create(PROVIDER_NAME).getString(OAuthConfigKeys.SERVICE_NAME, "SAP IAS"));
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);
    String rootUrl = OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL));
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
    linkExistingGerrit = cfg.getBoolean(OAuthConfigKeys.LINK_TO_EXISTING_GERRIT_ACCOUNT, false);
    boolean enablePKCE = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    // Descriptor: default HTTP Basic client auth, JSON token response, header bearer, scope
    // "openid profile email". SAP IAS may omit token_type, so tolerate it. Both the browser
    // code-exchange flow and the resource-owner password grant (getAccessToken below) run on this
    // client; the id_token is validated by the SAP CombiningValidator in getUserInfo.
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            String.format("%s/oauth2/authorize", rootUrl),
            String.format("%s/oauth2/token", rootUrl),
            "openid profile email",
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            /* tolerateMissingTokenType= */ true,
            enablePKCE);
    client = clientFactory.create(PROVIDER_NAME, endpoints);
    extIdScheme = OAuthServiceProviderExternalIdScheme.create(PROVIDER_NAME);
    this.tokenValidator = tokenValidator;
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    SapIdToken sapToken = new SapIdToken(getIdToken(token));
    ValidationResult res = tokenValidator.validate(sapToken);
    if (!res.isValid()) {
      log.warn("Invalid token received for " + sapToken.getClaimAsString("sub"));
      throw new IOException("Authentication error");
    }

    String username = sapToken.getClaimAsString("sub");
    String externalId = extIdScheme + ":" + username;
    String email = sapToken.getClaimAsString("email");
    String firstName = sapToken.getClaimAsString("first_name");
    String lastName = sapToken.getClaimAsString("last_name");
    String displayName =
        Strings.emptyToNull(
            Stream.of(firstName, lastName)
                .filter(s -> !Strings.isNullOrEmpty(s))
                .collect(Collectors.joining(" ")));
    String claimedIdentity = linkExistingGerrit ? "gerrit:" + username : null;
    return new OAuthUserInfo(externalId, username, email, displayName, claimedIdentity);
  }

  /** Exchanges resource-owner credentials for a token (resource-owner password flow). */
  public OAuthToken getAccessToken(String username, String password) {
    try {
      return client.passwordGrant(username, password);
    } catch (IOException e) {
      String msg = "Cannot retrieve access token";
      log.error(msg, e);
      throw new RuntimeException(msg, e);
    }
  }

  private static String getIdToken(OAuthToken token) {
    try {
      return new DefaultJsonObject(token.getRaw()).getAsString("id_token");
    } catch (IllegalStateException e) {
      return token.getToken();
    }
  }
}
