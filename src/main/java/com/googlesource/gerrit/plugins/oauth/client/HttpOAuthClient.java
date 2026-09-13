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

package com.googlesource.gerrit.plugins.oauth.client;

import static com.google.gerrit.json.OutputFormat.JSON;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthAuthorizationInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link OAuthClient} over {@link OAuthHttpTransport}, driven by an {@link
 * OAuthProviderEndpoints} descriptor -- the OAuth authorization-code client for every provider. A
 * pure JDK + Gson implementation, which is why the plugin depends on no third-party OAuth or JSON
 * library.
 *
 * <p>Emits the standard OAuth 2.0 wire shape: authorization-URL parameters with OAuth
 * percent-encoding, a form-encoded token request (client auth, {@code code}/{@code
 * redirect_uri}/{@code scope}/{@code grant_type}[/{@code code_verifier}]), and the configured
 * bearer placement on resource fetches. Holds no per-authorization state: the PKCE verifier is
 * generated per call and returned in {@link OAuthAuthorizationInfo}.
 */
public class HttpOAuthClient implements OAuthClient {
  private final OAuthProviderEndpoints endpoints;
  private final String clientId;
  private final String clientSecret;
  private final String callback;
  private final Gson gson;
  private final SecureRandom secureRandom;

  public HttpOAuthClient(
      OAuthProviderEndpoints endpoints, String clientId, String clientSecret, String callback) {
    // Fail fast on missing config: reject a blank client-id or client-secret before any request.
    // Public clients (no secret) would be a separate feature.
    this.endpoints = requireNonNull(endpoints, "endpoints");
    this.clientId = requireNonBlank(clientId, "client-id");
    this.clientSecret = requireNonBlank(clientSecret, "client-secret");
    this.callback = requireNonNull(callback, "callback");
    this.gson = JSON.newGson();
    this.secureRandom = new SecureRandom();
  }

  @Override
  public OAuthAuthorizationInfo getAuthorizationInfo() {
    if (!endpoints.enablePkce()) {
      return new OAuthAuthorizationInfo(buildAuthorizationUrl(/* codeChallenge= */ null), null);
    }
    String codeVerifier = newCodeVerifier();
    return new OAuthAuthorizationInfo(
        buildAuthorizationUrl(s256Challenge(codeVerifier)), codeVerifier);
  }

  private String buildAuthorizationUrl(@Nullable String codeChallenge) {
    List<String[]> params = new ArrayList<>();
    // PKCE params first, then the fixed response_type/client_id/redirect_uri/scope, in a stable
    // order. Authorization servers treat query parameters as unordered, so the exact order is not
    // significant; it is fixed here only for deterministic, testable output.
    if (codeChallenge != null) {
      params.add(new String[] {"code_challenge", codeChallenge});
      params.add(new String[] {"code_challenge_method", "S256"});
    }
    params.add(new String[] {"response_type", "code"});
    params.add(new String[] {"client_id", clientId});
    params.add(new String[] {"redirect_uri", callback});
    if (endpoints.scope() != null) {
      params.add(new String[] {"scope", endpoints.scope()});
    }
    // No state parameter: Gerrit core owns the OAuth state/CSRF round-trip.
    return appendQuery(endpoints.authorizationEndpoint(), params);
  }

  @Override
  public OAuthToken exchangeCode(OAuthVerifier verifier, @Nullable String codeVerifier)
      throws IOException {
    List<String[]> body = new ArrayList<>();
    addRequestBodyClientAuth(body);
    body.add(new String[] {"code", verifier.getValue()});
    body.add(new String[] {"redirect_uri", callback});
    if (endpoints.scope() != null) {
      body.add(new String[] {"scope", endpoints.scope()});
    }
    body.add(new String[] {"grant_type", "authorization_code"});
    if (endpoints.enablePkce()) {
      if (codeVerifier == null || codeVerifier.isBlank()) {
        throw new IOException("PKCE is enabled but no code_verifier is available");
      }
      body.add(new String[] {"code_verifier", codeVerifier});
    }
    return requestToken(body);
  }

  @Override
  public OAuthToken passwordGrant(String username, String password) throws IOException {
    // Password grant orders the body username, password, scope, grant_type, then appends
    // request-body client auth LAST -- unlike code exchange, where client auth comes first.
    List<String[]> body = new ArrayList<>();
    body.add(new String[] {"username", username});
    body.add(new String[] {"password", password});
    if (endpoints.scope() != null) {
      body.add(new String[] {"scope", endpoints.scope()});
    }
    body.add(new String[] {"grant_type", "password"});
    addRequestBodyClientAuth(body);
    return requestToken(body);
  }

  private OAuthToken requestToken(List<String[]> body) throws IOException {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/x-www-form-urlencoded");
    addBasicClientAuth(headers);
    OAuthHttpTransport.Response response =
        httpRequest("POST", endpoints.tokenEndpoint(), headers, formEncode(body));
    if (response.code < 200 || response.code >= 300) {
      throw new IOException(
          "Token endpoint rejected the request: HTTP " + response.code + " " + safeError(response));
    }
    return parseToken(response.body);
  }

  private void addRequestBodyClientAuth(List<String[]> body) {
    if (endpoints.clientAuthStyle() == ClientAuthStyle.REQUEST_BODY) {
      body.add(new String[] {"client_id", clientId});
      body.add(new String[] {"client_secret", clientSecret});
    }
  }

  private void addBasicClientAuth(Map<String, String> headers) {
    if (endpoints.clientAuthStyle() == ClientAuthStyle.BASIC) {
      String creds =
          Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(UTF_8));
      headers.put("Authorization", "Basic " + creds);
    }
  }

  private OAuthToken parseToken(String body) throws IOException {
    String accessToken;
    String tokenType;
    if (endpoints.tokenResponseFormat() == TokenResponseFormat.FORM_URL_ENCODED) {
      Map<String, String> form = parseFormEncoded(body);
      accessToken = form.get("access_token");
      tokenType = form.get("token_type");
    } else {
      JsonObject json;
      try {
        json = gson.fromJson(body, JsonObject.class);
      } catch (JsonSyntaxException e) {
        throw new IOException("Token response is not valid JSON", e);
      }
      if (json == null) {
        throw new IOException("Token response is empty");
      }
      accessToken = asString(json.get("access_token"));
      tokenType = asString(json.get("token_type"));
    }
    if (accessToken == null || accessToken.isEmpty()) {
      throw new IOException("Token response is missing access_token");
    }
    if (tokenType == null && endpoints.tolerateMissingTokenType()) {
      tokenType = "";
    }
    // Preserve the raw response so id_token / expiry consumers keep working.
    return new OAuthToken(accessToken, tokenType, body);
  }

  @Override
  public String get(URI resource, OAuthToken token) throws IOException {
    return get(resource, token, Map.of());
  }

  @Override
  public String get(URI resource, OAuthToken token, Map<String, String> extraHeaders)
      throws IOException {
    String url = resource.toString();
    Map<String, String> headers = new LinkedHashMap<>();
    if (endpoints.bearerPlacement() == BearerPlacement.URI_QUERY_ACCESS_TOKEN) {
      url = appendQuery(url, List.<String[]>of(new String[] {"access_token", token.getToken()}));
    } else {
      headers.put("Authorization", "Bearer " + token.getToken());
    }
    headers.putAll(extraHeaders);
    OAuthHttpTransport.Response response = httpRequest("GET", url, headers, null);
    if (response.code != 200) {
      throw new IOException(
          "Protected resource request failed: HTTP "
              + response.code
              + " for "
              + resource
              + " "
              + safeError(response));
    }
    return response.body;
  }

  @Override
  public String getVersion() {
    return "2.0";
  }

  /** Performs the HTTP call. Package-private so tests can supply canned responses. */
  OAuthHttpTransport.Response httpRequest(
      String method, String url, Map<String, String> headers, @Nullable String body)
      throws IOException {
    return OAuthHttpTransport.request(method, url, headers, body);
  }

  private String newCodeVerifier() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String s256Challenge(String codeVerifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** Appends {@code params} as a query string with OAuth percent-encoding. */
  private static String appendQuery(String url, List<String[]> params) {
    StringBuilder sb = new StringBuilder(url);
    char sep = url.indexOf('?') == -1 ? '?' : '&';
    for (String[] p : params) {
      sb.append(sep).append(encode(p[0])).append('=').append(encode(p[1]));
      sep = '&';
    }
    return sb.toString();
  }

  private static String formEncode(List<String[]> params) {
    StringBuilder sb = new StringBuilder();
    for (String[] p : params) {
      if (sb.length() > 0) {
        sb.append('&');
      }
      sb.append(encode(p[0])).append('=').append(encode(p[1]));
    }
    return sb.toString();
  }

  private static Map<String, String> parseFormEncoded(String body) {
    Map<String, String> out = new LinkedHashMap<>();
    if (body == null || body.isEmpty()) {
      return out;
    }
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String key = urlDecode(pair.substring(0, eq));
      String value = urlDecode(pair.substring(eq + 1));
      out.putIfAbsent(key, value);
    }
    return out;
  }

  /**
   * OAuth percent-encoding: URL encoding plus the RFC 5849 fixups (space, {@code *}, {@code ~}).
   */
  private static String encode(String plain) {
    String encoded = URLEncoder.encode(plain, UTF_8);
    return encoded.replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
  }

  private static String urlDecode(String s) {
    return java.net.URLDecoder.decode(s, UTF_8);
  }

  @Nullable
  private static String asString(@Nullable JsonElement e) {
    return e == null || e.isJsonNull() ? null : e.getAsString();
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be configured (non-blank)");
    }
    return value;
  }

  private static String safeError(OAuthHttpTransport.Response response) {
    // Surface a provider error code/description when present, but never the raw body wholesale
    // (which could contain a token on some endpoints).
    try {
      JsonElement parsed = JSON.newGson().fromJson(response.body, JsonElement.class);
      if (parsed != null && parsed.isJsonObject()) {
        JsonObject o = parsed.getAsJsonObject();
        String error = asString(o.get("error"));
        if (error != null) {
          String description = asString(o.get("error_description"));
          return description == null ? "(" + error + ")" : "(" + error + ": " + description + ")";
        }
      }
    } catch (JsonSyntaxException ignored) {
      // fall through
    }
    return "";
  }
}
