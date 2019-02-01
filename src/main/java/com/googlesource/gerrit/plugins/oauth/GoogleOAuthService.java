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

package com.googlesource.gerrit.plugins.oauth;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.server.OutputFormat;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import org.apache.commons.codec.binary.Base64;
import org.scribe.builder.ServiceBuilder;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletResponse;

@Singleton
class GoogleOAuthService implements OAuthServiceProvider {
  private static final Logger log =
      LoggerFactory.getLogger(GoogleOAuthService.class);
  static final String CONFIG_SUFFIX = "-google-oauth";
  private static final String PROTECTED_RESOURCE_URL =
      "https://www.googleapis.com/oauth2/v2/userinfo";
  private static final String SCOPE = "email profile";
  private final OAuthService service;
  private final String canonicalWebUrl;
  private final boolean linkToExistingOpenIDAccounts;
  private final String domain;
  private final boolean useEmailAsUsername;

  @Inject
  GoogleOAuthService(PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(
        pluginName + CONFIG_SUFFIX);
    this.canonicalWebUrl = CharMatcher.is('/').trimTrailingFrom(
        urlProvider.get()) + "/";
    this.linkToExistingOpenIDAccounts = cfg.getBoolean(
        InitOAuth.LINK_TO_EXISTING_OPENID_ACCOUNT, false);
    this.domain = cfg.getString(InitOAuth.DOMAIN);
    this.useEmailAsUsername = cfg.getBoolean(
        InitOAuth.USE_EMAIL_AS_USERNAME, false);
    String scope = linkToExistingOpenIDAccounts
        ? "openid " + SCOPE
        : SCOPE;
    this.service = new ServiceBuilder()
        .provider(Google2Api.class)
        .apiKey(cfg.getString(InitOAuth.CLIENT_ID))
        .apiSecret(cfg.getString(InitOAuth.CLIENT_SECRET))
        .callback(canonicalWebUrl + "oauth")
        .scope(scope)
        .build();
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: canonicalWebUrl={}", canonicalWebUrl);
      log.debug("OAuth2: scope={}", scope);
      log.debug("OAuth2: linkToExistingOpenIDAccounts={}",
          linkToExistingOpenIDAccounts);
      log.debug("OAuth2: domain={}", domain);
      log.debug("OAuth2: useEmailAsUsername={}", useEmailAsUsername);
    }
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    OAuthRequest request = new OAuthRequest(Verb.GET, PROTECTED_RESOURCE_URL);
    Token t =
        new Token(token.getToken(), token.getSecret(), token.getRaw());
    service.signRequest(t, request);
    Response response = request.send();
    if (response.getCode() != HttpServletResponse.SC_OK) {
      throw new IOException(String.format("Status %s (%s) for request %s",
          response.getCode(), response.getBody(), request.getUrl()));
    }
    JsonElement userJson =
        OutputFormat.JSON.newGson().fromJson(response.getBody(),
            JsonElement.class);
    if (log.isDebugEnabled()) {
      log.debug("User info response: {}", response.getBody());
    }
    if (userJson.isJsonObject()) {
      JsonObject jsonObject = userJson.getAsJsonObject();
      JsonElement id = jsonObject.get("id");
      if (id == null || id.isJsonNull()) {
        throw new IOException(String.format(
            "Response doesn't contain id field"));
      }
      JsonElement email = jsonObject.get("email");
      JsonElement name = jsonObject.get("name");
      String claimedIdentifier = null;
      String login = null;

      if (linkToExistingOpenIDAccounts
          || !Strings.isNullOrEmpty(domain)) {
        JsonObject jwtToken = retrieveJWTToken(token);
        if (linkToExistingOpenIDAccounts) {
          claimedIdentifier = retrieveClaimedIdentity(jwtToken);
        }
        if (!Strings.isNullOrEmpty(domain)) {
          String hdClaim = retrieveHostedDomain(jwtToken);
          if (!domain.equalsIgnoreCase(hdClaim)) {
            // TODO(davido): improve error reporting in OAuth extension point
            log.error("Error: hosted domain validation failed: {}",
                Strings.nullToEmpty(hdClaim));
            return null;
          }
        }
      }
      if (useEmailAsUsername && !email.isJsonNull()) {
        login = email.getAsString().split("@")[0];
      }
      return new OAuthUserInfo(id.getAsString() /*externalId*/,
          login /*username*/,
          email == null || email.isJsonNull() ? null : email.getAsString() /*email*/,
          name == null || name.isJsonNull() ? null : name.getAsString() /*displayName*/,
	      claimedIdentifier /*claimedIdentity*/);
    } else {
        throw new IOException(String.format(
            "Invalid JSON '%s': not a JSON Object", userJson));
    }
  }

  private JsonObject retrieveJWTToken(OAuthToken token) {
    JsonElement idToken =
        OutputFormat.JSON.newGson().fromJson(token.getRaw(), JsonElement.class);
    if (idToken != null && idToken.isJsonObject()) {
      JsonObject idTokenObj = idToken.getAsJsonObject();
      JsonElement idTokenElement = idTokenObj.get("id_token");
      if (idTokenElement != null && !idTokenElement.isJsonNull()) {
        String payload = decodePayload(idTokenElement.getAsString());
        if (!Strings.isNullOrEmpty(payload)) {
          JsonElement tokenJsonElement =
            OutputFormat.JSON.newGson().fromJson(payload, JsonElement.class);
          if (tokenJsonElement.isJsonObject()) {
            return tokenJsonElement.getAsJsonObject();
          }
        }
      }
    }
    return null;
  }

  private static String retrieveClaimedIdentity(JsonObject jwtToken) {
    JsonElement openidIdElement = jwtToken.get("openid_id");
    if (openidIdElement != null && !openidIdElement.isJsonNull()) {
      String openIdId = openidIdElement.getAsString();
      log.debug("OAuth2: openid_id={}", openIdId);
      return openIdId;
    }
    log.debug("OAuth2: JWT doesn't contain openid_id element");
    return null;
  }

  private static String retrieveHostedDomain(JsonObject jwtToken) {
    JsonElement hdClaim = jwtToken.get("hd");
    if (hdClaim != null && !hdClaim.isJsonNull()) {
      String hd = hdClaim.getAsString();
      log.debug("OAuth2: hd={}", hd);
      return hd;
    }
    log.debug("OAuth2: JWT doesn't contain hd element");
    return null;
  }

  /**
   * Decode payload from JWT according to spec:
   * "header.payload.signature"
   *
   * @param idToken Base64 encoded tripple, separated with dot
   * @return openid_id part of payload, when contained, null otherwise
   */
  private static String decodePayload(String idToken) {
    Preconditions.checkNotNull(idToken);
    String[] jwtParts = idToken.split("\\.");
    Preconditions.checkState(jwtParts.length == 3);
    String payloadStr = jwtParts[1];
    Preconditions.checkNotNull(payloadStr);
    return new String(Base64.decodeBase64(payloadStr));
  }

  @Override
  public OAuthToken getAccessToken(OAuthVerifier rv) {
    Verifier vi = new Verifier(rv.getValue());
    Token to = service.getAccessToken(null, vi);
    OAuthToken result = new OAuthToken(to.getToken(),
        to.getSecret(), to.getRawResponse());
     return result;
  }

  @Override
  public String getAuthorizationUrl() {
    String url = service.getAuthorizationUrl(null);
    try {
      if (linkToExistingOpenIDAccounts) {
        url += "&openid.realm=" + URLEncoder.encode(canonicalWebUrl,
            StandardCharsets.UTF_8.name());
      }
      if (!Strings.isNullOrEmpty(domain)) {
        url += "&hd=" + URLEncoder.encode(domain,
            StandardCharsets.UTF_8.name());
      }
    } catch (UnsupportedEncodingException e) {
      throw new IllegalArgumentException(e);
    }
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: authorization URL={}", url);
    }
    return url;
  }

  @Override
  public String getVersion() {
    return service.getVersion();
  }

  @Override
  public String getName() {
    return "Google OAuth2";
  }
}
