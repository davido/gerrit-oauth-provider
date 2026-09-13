// Copyright (C) 2026 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.discovery;

import static com.google.gerrit.json.OutputFormat.JSON;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
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
import com.googlesource.gerrit.plugins.oauth.client.OAuthHttpTransport;
import com.googlesource.gerrit.plugins.oauth.client.OAuthProviderEndpoints;
import com.googlesource.gerrit.plugins.oauth.client.TokenResponseFormat;
import com.googlesource.gerrit.plugins.oauth.jwt.OidcJwtValidator;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

@Singleton
@OAuthServiceProviderConfig(name = DiscoveryOAuthService.PROVIDER_NAME)
public class DiscoveryOAuthService extends StandardResourceOAuthService {
  public static final String PROVIDER_NAME = "discovery";
  private static final String WELL_KNOWN_PATH = "/.well-known/openid-configuration";
  private static final String SCOPE = "openid profile email";
  // External-id scheme is an account-identity input, so restrict it to the default plus the OIDC
  // wrappers targeted for Discovery consolidation -- not an arbitrary string. dex-oauth is
  // intentionally excluded: Dex maps its external id from `email`, not `sub`, so preserving only
  // the scheme would still emit dex-oauth:<sub> and relink accounts. Dex needs a separate
  // external-id-claim knob first.
  private static final ImmutableSet<String> ALLOWED_EXTERNAL_ID_SCHEMES =
      ImmutableSet.of(
          "discovery-oauth",
          "auth0-oauth",
          "authentik-oauth",
          "cognito-oauth",
          "tuleap-oauth",
          "llng-oauth");

  private final String userinfoEndpoint;
  private final DiscoveryUserInfoMapper userInfoMapper;
  @Nullable private final OidcJwtValidator validator;

  @Inject
  DiscoveryOAuthService(OAuthPluginConfigFactory cfgFactory, HttpOAuthClientFactory clientFactory) {
    this(cfgFactory, clientFactory, /* providedValidator= */ null);
  }

  @VisibleForTesting
  DiscoveryOAuthService(
      OAuthPluginConfigFactory cfgFactory,
      HttpOAuthClientFactory clientFactory,
      @Nullable OidcJwtValidator providedValidator) {
    super("Discovery OAuth2");
    PluginConfig cfg = cfgFactory.create(PROVIDER_NAME);

    String rootUrl = OAuthUrls.trimTrailingSlashes(cfg.getString(OAuthConfigKeys.ROOT_URL));
    URI rootUri = validateRootUrl(rootUrl);

    DiscoveryOpenIdConnect discovery = fetchDiscoveryDocument(rootUri.toString() + WELL_KNOWN_PATH);
    validateDiscoveryDocument(discovery);

    boolean enablePKCE = cfg.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false);
    // Discovery: endpoints from the fetched OIDC discovery document, the
    // client-auth-method knob (Basic default or request-body), JSON token response, header-bearer
    // userinfo GET, openid-profile-email scope. It verifies the id_token (raw body) and fetches
    // userinfo, so both HttpOAuthClient paths are exercised.
    OAuthProviderEndpoints endpoints =
        new OAuthProviderEndpoints(
            discovery.getAuthorizationEndpoint(),
            discovery.getTokenEndpoint(),
            SCOPE,
            resolveClientAuthStyle(cfg.getString(OAuthConfigKeys.CLIENT_AUTH_METHOD)),
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            /* tolerateMissingTokenType= */ false,
            enablePKCE);
    client = clientFactory.create(PROVIDER_NAME, endpoints);

    userinfoEndpoint = discovery.getUserinfoEndpoint();
    boolean linkToExistingGerrit =
        cfg.getBoolean(OAuthConfigKeys.LINK_TO_EXISTING_GERRIT_ACCOUNT, false);
    userInfoMapper =
        new DiscoveryUserInfoMapper(
            resolveExternalIdScheme(cfg.getString(OAuthConfigKeys.EXTERNAL_ID_SCHEME)),
            linkToExistingGerrit);
    this.validator =
        providedValidator != null
            ? providedValidator
            : buildValidatorIfAvailable(discovery, cfg.getString(OAuthConfigKeys.CLIENT_ID));

    if (log.isDebugEnabled()) {
      log.debug("OAuth2: discovery issuer={}", discovery.getIssuer());
      log.debug("OAuth2: authorization endpoint={}", discovery.getAuthorizationEndpoint());
      log.debug("OAuth2: token endpoint={}", discovery.getTokenEndpoint());
      log.debug("OAuth2: userinfo endpoint={}", discovery.getUserinfoEndpoint());
    }
  }

  /**
   * Resolves the external-id scheme: the configured value if valid, else the default {@code
   * discovery-oauth}. Blank/unset uses the default; a present-but-disallowed value (not in the
   * allowlist) fails fast because it determines account identity. Lets an operator migrating a
   * removed wrapper (e.g. Auth0) to Discovery keep the wrapper's scheme ({@code auth0-oauth}) so
   * existing accounts are not unlinked.
   */
  private static String resolveExternalIdScheme(@Nullable String configured) {
    if (configured == null || configured.isBlank()) {
      return OAuthServiceProviderExternalIdScheme.create(PROVIDER_NAME);
    }
    String scheme = configured.trim();
    if (!ALLOWED_EXTERNAL_ID_SCHEMES.contains(scheme)) {
      throw new ProvisionException(
          "Invalid external-id-scheme '"
              + configured
              + "': allowed values are "
              + ALLOWED_EXTERNAL_ID_SCHEMES
              + " (the default plus the OIDC wrappers targeted for Discovery consolidation).");
    }
    return scheme;
  }

  /**
   * Resolves the token-endpoint client authentication method: {@code basic} (default, HTTP Basic
   * per RFC 6749 §2.3.1) or {@code request-body} (client_id/client_secret in the POST body). Lets
   * an operator migrating a wrapper whose IdP only accepts request-body auth (e.g. a default
   * LemonLDAP::NG deployment) onto Discovery without changing the IdP. Blank/unset uses {@code
   * basic}; an unknown value fails fast.
   */
  private static ClientAuthStyle resolveClientAuthStyle(@Nullable String configured) {
    if (configured == null || configured.isBlank()) {
      return ClientAuthStyle.BASIC;
    }
    switch (configured.trim()) {
      case "basic":
        return ClientAuthStyle.BASIC;
      case "request-body":
        return ClientAuthStyle.REQUEST_BODY;
      default:
        throw new ProvisionException(
            "Invalid client-auth-method '"
                + configured
                + "': allowed values are 'basic' and 'request-body'.");
    }
  }

  /** The resolved user-info mapper, shared with the Git path so both emit the same external ids. */
  DiscoveryUserInfoMapper userInfoMapper() {
    return userInfoMapper;
  }

  private URI validateRootUrl(String rootUrl) {
    return validateUrl(
        rootUrl,
        "Root URL must be configured",
        "Root URL is not a valid URL",
        "Root URL must be absolute URL",
        "Root URL must use http or https");
  }

  private void validateDiscoveryDocument(DiscoveryOpenIdConnect discovery) {
    if (discovery == null) {
      throw new ProvisionException("Discovery document is empty");
    }

    validateUrlField("issuer", discovery.getIssuer());
    validateUrlField("authorization_endpoint", discovery.getAuthorizationEndpoint());
    validateUrlField("token_endpoint", discovery.getTokenEndpoint());
    validateUrlField("userinfo_endpoint", discovery.getUserinfoEndpoint());
  }

  private void validateUrlField(String fieldName, String value) {
    validateUrl(
        value,
        "Discovery document missing required field: " + fieldName,
        "Discovery document field is not a valid URL: " + fieldName,
        "Discovery document field must be absolute URL: " + fieldName,
        "Discovery document field must use http or https: " + fieldName);
  }

  private static URI validateUrl(
      String value,
      String missingMessage,
      String invalidMessage,
      String absoluteMessage,
      String schemeMessage) {
    if (value == null || value.isBlank()) {
      throw new ProvisionException(missingMessage);
    }

    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new ProvisionException(invalidMessage, e);
    }

    if (!uri.isAbsolute()) {
      throw new ProvisionException(absoluteMessage);
    }

    if (uri.getScheme() == null
        || (!"http".equalsIgnoreCase(uri.getScheme())
            && !"https".equalsIgnoreCase(uri.getScheme()))) {
      throw new ProvisionException(schemeMessage);
    }

    return uri;
  }

  DiscoveryOpenIdConnect fetchDiscoveryDocument(String discoveryUrl) {
    try {
      OAuthHttpTransport.Response response =
          OAuthHttpTransport.request("GET", discoveryUrl, Map.of(), null);
      if (response.code != HttpServletResponse.SC_OK) {
        log.error(
            "Failed to fetch OIDC discovery from {}. Status: {}. Response: {}",
            discoveryUrl,
            response.code,
            response.body);
        throw new IOException("HTTP " + response.code);
      }
      return JSON.newGson().fromJson(response.body, DiscoveryOpenIdConnect.class);
    } catch (IOException e) {
      throw new ProvisionException(
          "Cannot fetch OpenID Connect discovery document: " + discoveryUrl, e);
    }
  }

  /**
   * Builds a validator from the discovery document's {@code jwks_uri}/{@code issuer}, audience
   * pinned to {@code client-id}. Returns {@code null} (disabling the id_token check) when {@code
   * jwks_uri} or {@code client-id} is absent.
   */
  @Nullable
  private OidcJwtValidator buildValidatorIfAvailable(
      DiscoveryOpenIdConnect discovery, @Nullable String clientId) {
    String jwksUri = discovery.getJwksUri();
    if (jwksUri == null || jwksUri.isBlank()) {
      log.warn(
          "Discovery document at issuer {} does not expose jwks_uri; id_token validation is"
              + " disabled on the browser flow.",
          discovery.getIssuer());
      return null;
    }
    if (clientId == null) {
      log.warn("client-id not configured; cannot build JWKS validator for Discovery");
      return null;
    }
    return OidcJwtValidator.builder()
        .jwksUri(jwksUri)
        .issuer(discovery.getIssuer())
        .audience(clientId)
        .build();
  }

  /**
   * Verifies the {@code id_token} against the IdP's JWKS; skipped when no validator is available.
   */
  @Override
  @Nullable
  protected String verifyToken(OAuthToken token) throws IOException {
    if (validator == null) {
      return null;
    }
    JsonElement tokenJson = JSON.newGson().fromJson(token.getRaw(), JsonElement.class);
    if (tokenJson == null || !tokenJson.isJsonObject()) {
      throw new IOException("Token response is not a JSON object");
    }
    JsonElement idToken = tokenJson.getAsJsonObject().get("id_token");
    if (idToken == null || idToken.isJsonNull()) {
      throw new IOException("Token response is missing id_token");
    }
    // sub is required on an id_token; reject its absence so the userinfo binding cannot be
    // bypassed.
    String subject = validator.validate(idToken.getAsString()).subject();
    if (subject == null || subject.isBlank()) {
      throw new IOException("id_token is missing the required sub claim");
    }
    return subject;
  }

  @Override
  @Nullable
  protected String resourceSubject(String body) throws IOException {
    JsonElement userJson = JSON.newGson().fromJson(body, JsonElement.class);
    if (userJson != null && userJson.isJsonObject()) {
      JsonElement sub = userJson.getAsJsonObject().get("sub");
      if (sub != null && !sub.isJsonNull()) {
        return sub.getAsString();
      }
    }
    return null;
  }

  /** JWKS validator from the discovery document, or {@code null} when it exposed no jwks_uri. */
  @Nullable
  OidcJwtValidator validator() {
    return validator;
  }

  @Override
  protected String resourceUrl() {
    return userinfoEndpoint;
  }

  @Override
  protected OAuthUserInfo parseUserInfo(String body) throws IOException {
    JsonElement userJson = JSON.newGson().fromJson(body, JsonElement.class);
    if (userJson != null && userJson.isJsonObject()) {
      return userInfoMapper.mapForBrowser(userJson.getAsJsonObject());
    }
    throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
  }
}
