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

import static com.google.gerrit.json.OutputFormat.JSON;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
class GoogleOAuthService implements OAuthServiceProvider {
  private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);
  static final String CONFIG_SUFFIX = "-google-oauth";
  private static final String GOOGLE_PROVIDER_PREFIX = "google-oauth:";
  private static final String PROTECTED_RESOURCE_URL =
      "https://www.googleapis.com/oauth2/v2/userinfo";
  private static final String SCOPE = "email profile";
  private final OAuth20Service service;
  private final String canonicalWebUrl;
  private final List<String> domains;
  private final boolean useEmailAsUsername;
  private final boolean fixLegacyUserId;

  @Inject
  GoogleOAuthService(
      PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName + CONFIG_SUFFIX);
    this.canonicalWebUrl = CharMatcher.is('/').trimTrailingFrom(urlProvider.get()) + "/";
    if (cfg.getBoolean(InitOAuth.LINK_TO_EXISTING_OPENID_ACCOUNT, false)) {
      log.warn(
          String.format(
              "The support for: %s is disconinued", InitOAuth.LINK_TO_EXISTING_OPENID_ACCOUNT));
    }
    fixLegacyUserId = cfg.getBoolean(InitOAuth.FIX_LEGACY_USER_ID, false);
    this.domains = Arrays.asList(cfg.getStringList(InitOAuth.DOMAIN));
    this.useEmailAsUsername = cfg.getBoolean(InitOAuth.USE_EMAIL_AS_USERNAME, false);
    this.service =
        new ServiceBuilder(cfg.getString(InitOAuth.CLIENT_ID))
            .apiSecret(cfg.getString(InitOAuth.CLIENT_SECRET))
            .callback(canonicalWebUrl + "oauth")
            .defaultScope(SCOPE)
            .build(new Google2Api());
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: canonicalWebUrl={}", canonicalWebUrl);
      log.debug("OAuth2: scope={}", SCOPE);
      log.debug("OAuth2: domains={}", domains);
      log.debug("OAuth2: useEmailAsUsername={}", useEmailAsUsername);
    }
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    OAuthRequest request = new OAuthRequest(Verb.GET, PROTECTED_RESOURCE_URL);
    OAuth2AccessToken t = new OAuth2AccessToken(token.getToken(), token.getRaw());
    service.signRequest(t, request);

    JsonElement userJson = null;
    try (Response response = service.execute(request)) {
      if (response.getCode() != HttpServletResponse.SC_OK) {
        throw new IOException(
            String.format(
                "Status %s (%s) for request %s",
                response.getCode(), response.getBody(), request.getUrl()));
      }
      userJson = JSON.newGson().fromJson(response.getBody(), JsonElement.class);
      if (log.isDebugEnabled()) {
        log.debug("User info response: {}", response.getBody());
      }
      if (userJson.isJsonObject()) {
        JsonObject jsonObject = userJson.getAsJsonObject();
        JsonElement id = jsonObject.get("id");
        if (id == null || id.isJsonNull()) {
          throw new IOException("Response doesn't contain id field");
        }
        JsonElement email = jsonObject.get("email");
        JsonElement name = jsonObject.get("name");
        String login = null;

        if (domains.size() > 0) {
          boolean domainMatched = false;
          JsonObject jwtToken = retrieveJWTToken(token);
          String hdClaim = retrieveHostedDomain(jwtToken);
          for (String domain : domains) {
            if (domain.equalsIgnoreCase(hdClaim)) {
              domainMatched = true;
              break;
            }
          }
          if (!domainMatched) {
            // TODO(davido): improve error reporting in OAuth extension point
            log.error("Error: hosted domain validation failed: {}", Strings.nullToEmpty(hdClaim));
            return null;
          }
        }
        if (useEmailAsUsername && !email.isJsonNull()) {
          login = email.getAsString().split("@")[0];
        }
        return new OAuthUserInfo(
            GOOGLE_PROVIDER_PREFIX + id.getAsString() /*externalId*/,
            login /*username*/,
            email == null || email.isJsonNull() ? null : email.getAsString() /*email*/,
            name == null || name.isJsonNull() ? null : name.getAsString() /*displayName*/,
            fixLegacyUserId ? id.getAsString() : null /*claimedIdentity*/);
      }
    } catch (ExecutionException | InterruptedException e) {
      throw new RuntimeException("Cannot retrieve user info resource", e);
    }

    throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
  }

  private JsonObject retrieveJWTToken(OAuthToken token) throws IOException {
    JsonElement idToken = JSON.newGson().fromJson(token.getRaw(), JsonElement.class);
    if (idToken != null && idToken.isJsonObject()) {
      JsonObject idTokenObj = idToken.getAsJsonObject();
      JsonElement idTokenElement = idTokenObj.get("id_token");
      if (idTokenElement != null && !idTokenElement.isJsonNull()) {
        String payload;
        try {
          payload = decodePayload(idTokenElement.getAsString());
        } catch (UnsupportedEncodingException e) {
          throw new IOException(
              String.format(
                  "%s support is required to interact with JWTs", StandardCharsets.UTF_8.name()),
              e);
        }
        if (!Strings.isNullOrEmpty(payload)) {
          JsonElement tokenJsonElement = JSON.newGson().fromJson(payload, JsonElement.class);
          if (tokenJsonElement.isJsonObject()) {
            return tokenJsonElement.getAsJsonObject();
          }
        }
      }
    }
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
   * Decode payload from JWT according to spec: "header.payload.signature"
   *
   * @param idToken Base64 encoded tripple, separated with dot
   * @return openid_id part of payload, when contained, null otherwise
   */
  private static String decodePayload(String idToken) throws UnsupportedEncodingException {
    Preconditions.checkNotNull(idToken);
    String[] jwtParts = idToken.split("\\.");
    Preconditions.checkState(jwtParts.length == 3);
    String payloadStr = jwtParts[1];
    Preconditions.checkNotNull(payloadStr);
    return new String(Base64.decodeBase64(payloadStr), StandardCharsets.UTF_8.name());
  }

  @Override
  public OAuthToken getAccessToken(OAuthVerifier rv) {
    try {
      OAuth2AccessToken accessToken = service.getAccessToken(rv.getValue());
      return new OAuthToken(
          accessToken.getAccessToken(), accessToken.getTokenType(), accessToken.getRawResponse());
    } catch (InterruptedException | ExecutionException | IOException e) {
      String msg = "Cannot retrieve access token";
      log.error(msg, e);
      throw new RuntimeException(msg, e);
    }
  }

  @Override
  public String getAuthorizationUrl() {
    StringBuilder urlBuilder = new StringBuilder(service.getAuthorizationUrl());
    try {
      if (domains.size() == 1) {
        urlBuilder.append("&hd=");
        urlBuilder.append(URLEncoder.encode(domains.get(0), StandardCharsets.UTF_8.name()));
      } else if (domains.size() > 1) {
        urlBuilder.append("&hd=*");
      }
    } catch (UnsupportedEncodingException e) {
      throw new IllegalArgumentException(e);
    }
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: authorization URL={}", urlBuilder);
    }
    return urlBuilder.toString();
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
