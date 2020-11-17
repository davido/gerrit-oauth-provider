// Copyright (C) 2018 The Android Open Source Project
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

import com.github.scribejava.apis.MicrosoftAzureActiveDirectory20Api;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.exceptions.OAuthException;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
class Office365OAuthService implements OAuthServiceProvider {
  private static final Logger log = LoggerFactory.getLogger(Office365OAuthService.class);
  static final String CONFIG_SUFFIX = "-office365-oauth";
  private static final String OFFICE365_PROVIDER_PREFIX = "office365-oauth:";
  private static final String PROTECTED_RESOURCE_URL = "https://graph.microsoft.com/v1.0/me";
  private static final String SCOPE =
      "openid offline_access https://graph.microsoft.com/user.readbasic.all";
  private static final String DEFAULT_TENANT = "organizations";
  private static final ImmutableSet<String> TENANTS_WITHOUT_VALIDATION =
      ImmutableSet.<String>builder().add(DEFAULT_TENANT).add("common").add("consumers").build();
  private final OAuth20Service service;
  private final Gson gson;
  private final String canonicalWebUrl;
  private final boolean useEmailAsUsername;
  private final String tenant;
  private final String clientId;

  @Inject
  Office365OAuthService(
      PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName + CONFIG_SUFFIX);
    this.canonicalWebUrl = CharMatcher.is('/').trimTrailingFrom(urlProvider.get()) + "/";
    this.useEmailAsUsername = cfg.getBoolean(InitOAuth.USE_EMAIL_AS_USERNAME, false);
    this.tenant = cfg.getString(InitOAuth.TENANT, DEFAULT_TENANT);
    this.clientId = cfg.getString(InitOAuth.CLIENT_ID);
    this.service =
        new ServiceBuilder(cfg.getString(InitOAuth.CLIENT_ID))
            .apiSecret(cfg.getString(InitOAuth.CLIENT_SECRET))
            .callback(canonicalWebUrl + "oauth")
            .defaultScope(SCOPE)
            .build(MicrosoftAzureActiveDirectory20Api.custom(tenant));
    this.gson = JSON.newGson();
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: canonicalWebUrl={}", canonicalWebUrl);
      log.debug("OAuth2: scope={}", SCOPE);
      log.debug("OAuth2: useEmailAsUsername={}", useEmailAsUsername);
    }
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    // ?: Have we set a custom tenant and is this a tenant other than the one set in
    // TENANTS_WITHOUT_VALIDATION
    if (!TENANTS_WITHOUT_VALIDATION.contains(tenant)) {
      // -> Yes, we are using a tenant that should be validated, so verify that is issued for the
      // same one that we
      // have set.
      String tid = getTokenJson(token.getToken()).get("tid").getAsString();

      // ?: Verify that this token has the same tenant as we are currently using
      if (!tenant.equals(tid)) {
        // -> No, this tenant does not equals the one in the token. So we should stop processing
        log.warn(
            String.format(
                "The token was issued by the tenant [%s] while we are set to use [%s]",
                tid, tenant));
        // Return null so the user will be shown Unauthorized.
        return null;
      }
    }

    // Due to scribejava does not expose the id_token we need to do this a bit convoluted way to
    // extract this our self
    // see <a href="https://github.com/scribejava/scribejava/issues/968">Obtaining id_token from
    // access_token</a> for
    // the scribejava issue on this.
    String rawToken = token.getRaw();
    JsonObject jwtJson = gson.fromJson(rawToken, JsonObject.class);
    String idTokenBase64 = jwtJson.get("id_token").getAsString();
    String aud = getTokenJson(idTokenBase64).get("aud").getAsString();

    // ?: Does this token have the same clientId set in the 'aud' part of the id_token as we are
    // using.
    // If not we should reject it
    // see <a href="https://docs.microsoft.com/en-us/azure/active-directory/develop/id-tokens">id
    // tokens Payload claims></a>
    // for information on the aud claim.
    if (!clientId.equals(aud)) {
      log.warn(
          String.format(
              "The id_token had aud [%s] while we expected it to be equal to the clientId [%s]",
              aud, clientId));
      // Return null so the user will be shown Unauthorized.
      return null;
    }

    OAuthRequest request = new OAuthRequest(Verb.GET, PROTECTED_RESOURCE_URL);
    OAuth2AccessToken t = new OAuth2AccessToken(token.getToken(), token.getRaw());
    service.signRequest(t, request);
    request.addHeader("Accept", "*/*");

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
        JsonElement email = jsonObject.get("mail");
        JsonElement name = jsonObject.get("displayName");
        String login = null;

        if (useEmailAsUsername && !email.isJsonNull()) {
          login = email.getAsString().split("@")[0];
        }
        return new OAuthUserInfo(
            OFFICE365_PROVIDER_PREFIX + id.getAsString() /*externalId*/,
            login /*username*/,
            email == null || email.isJsonNull() ? null : email.getAsString() /*email*/,
            name == null || name.isJsonNull() ? null : name.getAsString() /*displayName*/,
            null);
      }
    } catch (ExecutionException | InterruptedException e) {
      throw new RuntimeException("Cannot retrieve user info resource", e);
    }

    throw new IOException(String.format("Invalid JSON '%s': not a JSON Object", userJson));
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
    String url = service.getAuthorizationUrl();
    return url;
  }

  @Override
  public String getVersion() {
    return service.getVersion();
  }

  @Override
  public String getName() {
    return "Office365 OAuth2";
  }

  /** Get the {@link JsonObject} of a given token. */
  private JsonObject getTokenJson(String tokenBase64) {
    String[] tokenParts = tokenBase64.split("\\.");
    if (tokenParts.length != 3) {
      throw new OAuthException("Token does not contain expected number of parts");
    }

    // Extract the payload part from the JWT token (header.payload.signature) by retrieving
    // tokenParts[1].
    return gson.fromJson(
        new String(Base64.getDecoder().decode(tokenParts[1]), StandardCharsets.UTF_8),
        JsonObject.class);
  }
}
