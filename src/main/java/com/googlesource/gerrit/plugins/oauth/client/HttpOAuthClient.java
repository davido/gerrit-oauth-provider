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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.auth.oauth.OAuthAuthorizationInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthRevokedException;
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
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final OAuthProviderEndpoints endpoints;
  private final String clientId;
  private final String clientSecret;
  private final String callback;
  @Nullable private final String providerId;
  private final Gson gson;
  private final SecureRandom secureRandom;

  public HttpOAuthClient(
      OAuthProviderEndpoints endpoints, String clientId, String clientSecret, String callback) {
    this(endpoints, clientId, clientSecret, callback, /* providerId= */ null);
  }

  /**
   * @param providerId the {@code "pluginName:exportName"} id stamped into every minted {@link
   *     OAuthToken}, so core can resolve the issuing provider later for refresh/revoke-on-read; may
   *     be {@code null} (e.g. in tests).
   */
  public HttpOAuthClient(
      OAuthProviderEndpoints endpoints,
      String clientId,
      String clientSecret,
      String callback,
      @Nullable String providerId) {
    // Fail fast on missing config: reject a blank client-id or client-secret before any request.
    // Public clients (no secret) would be a separate feature.
    this.endpoints = requireNonNull(endpoints, "endpoints");
    this.clientId = requireNonBlank(clientId, "client-id");
    this.clientSecret = requireNonBlank(clientSecret, "client-secret");
    this.callback = requireNonNull(callback, "callback");
    this.providerId = providerId;
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
    OAuthHttpTransport.Response response = postForm(body);
    if (response.code < 200 || response.code >= 300) {
      throw new IOException(
          "Token endpoint rejected the request: HTTP " + response.code + " " + safeError(response));
    }
    return parseToken(response.body);
  }

  /** POSTs a form-encoded body to the token endpoint with the configured client authentication. */
  private OAuthHttpTransport.Response postForm(List<String[]> body) throws IOException {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/x-www-form-urlencoded");
    addBasicClientAuth(headers);
    return httpRequest("POST", endpoints.tokenEndpoint(), headers, formEncode(body));
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
    Long expiresInSeconds;
    if (endpoints.tokenResponseFormat() == TokenResponseFormat.FORM_URL_ENCODED) {
      Map<String, String> form = parseFormEncoded(body);
      accessToken = form.get("access_token");
      tokenType = form.get("token_type");
      expiresInSeconds = parseLongOrNull(form.get("expires_in"));
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
      expiresInSeconds = asLong(json.get("expires_in"));
    }
    if (accessToken == null || accessToken.isEmpty()) {
      throw new IOException("Token response is missing access_token");
    }
    if (tokenType == null && endpoints.tolerateMissingTokenType()) {
      tokenType = "";
    }
    // expires_in is relative seconds; absent means "unknown", which OAuthToken models as MAX_VALUE.
    long expiresAt =
        expiresInSeconds == null
            ? Long.MAX_VALUE
            : System.currentTimeMillis() + expiresInSeconds * 1000L;
    // Preserve the raw response so id_token / refresh-token / expiry consumers keep working.
    return new OAuthToken(accessToken, tokenType, body, expiresAt, providerId);
  }

  @Override
  public OAuthToken refresh(OAuthToken expiredToken) throws IOException {
    String refreshToken = extractRefreshToken(expiredToken.getRaw());
    if (refreshToken == null || refreshToken.isEmpty()) {
      // No refresh token (offline access never granted). Not revocation -- a plain IOException so
      // the caller treats it as a transient/unsupported case (fail-open by policy), not a logout.
      throw new IOException("No refresh_token available to refresh the access token");
    }
    logger.atFine().log(
        "OAuth refresh: POST grant_type=refresh_token to %s", endpoints.tokenEndpoint());
    List<String[]> body = new ArrayList<>();
    addRequestBodyClientAuth(body);
    body.add(new String[] {"grant_type", "refresh_token"});
    body.add(new String[] {"refresh_token", refreshToken});
    OAuthHttpTransport.Response response = postForm(body);
    if (response.code == 400 && isInvalidGrant(response.body)) {
      logger.atFine().log("OAuth refresh rejected with invalid_grant: refresh token revoked");
      throw new OAuthRevokedException("Refresh token rejected by the IdP (invalid_grant)");
    }
    if (response.code < 200 || response.code >= 300) {
      throw new IOException(
          "Refresh request failed: HTTP " + response.code + " " + safeError(response));
    }
    // Raw-merge: many providers (e.g. Google) omit refresh_token on refresh; carry the prior one
    // forward so the next refresh still has a token.
    OAuthToken refreshed = parseToken(mergeRefreshToken(response.body, refreshToken));
    logger.atFine().log(
        "OAuth refresh succeeded (HTTP %d): new access token expiresAt=%d",
        response.code, refreshed.getExpiresAt());
    return refreshed;
  }

  @Override
  public boolean supportsRevoke() {
    return endpoints.revocationEndpoint() != null;
  }

  @Override
  public void revoke(OAuthToken token) throws IOException {
    String revocationEndpoint = endpoints.revocationEndpoint();
    if (revocationEndpoint == null) {
      throw new UnsupportedOperationException("Provider has no revocation endpoint configured");
    }
    // Prefer the refresh token: revoking it invalidates the whole grant (access + refresh). Google
    // otherwise revokes only the presented token. Fall back to the access token when offline access
    // was never granted.
    String refreshToken = extractRefreshToken(token.getRaw());
    boolean haveRefresh = refreshToken != null && !refreshToken.isEmpty();
    String toRevoke = haveRefresh ? refreshToken : token.getToken();
    if (toRevoke == null || toRevoke.isEmpty()) {
      throw new IOException("No token available to revoke");
    }
    String hint = haveRefresh ? "refresh_token" : "access_token";
    List<String[]> body = new ArrayList<>();
    body.add(new String[] {"token", toRevoke});
    body.add(new String[] {"token_type_hint", hint});
    // Request-body client auth only (Google's revocation endpoint takes token-only; adding Basic
    // auth is not required and is omitted to match the verified request shape).
    addRequestBodyClientAuth(body);
    logger.atFine().log("OAuth revoke: POST token_type_hint=%s to %s", hint, revocationEndpoint);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/x-www-form-urlencoded");
    OAuthHttpTransport.Response response =
        httpRequest("POST", revocationEndpoint, headers, formEncode(body));
    // RFC 7009 §2.2: a successful or unnecessary revocation returns 200; an unknown/already-invalid
    // token is a no-op, which Google signals as 400 invalid_token.
    if (response.code == 400 && isInvalidToken(response.body)) {
      logger.atFine().log("OAuth revoke: token already invalid at the IdP (no-op)");
      return;
    }
    if (response.code < 200 || response.code >= 300) {
      throw new IOException(
          "Revocation request failed: HTTP " + response.code + " " + safeError(response));
    }
    logger.atFine().log("OAuth revoke succeeded (HTTP %d)", response.code);
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

  /** Reads {@code refresh_token} from a stored raw token response (JSON, then form-encoded). */
  @Nullable
  private String extractRefreshToken(@Nullable String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    try {
      JsonObject json = gson.fromJson(raw, JsonObject.class);
      if (json != null && json.has("refresh_token")) {
        return asString(json.get("refresh_token"));
      }
    } catch (JsonSyntaxException ignored) {
      // Not JSON; fall through to form parsing.
    }
    return parseFormEncoded(raw).get("refresh_token");
  }

  /**
   * If {@code newBody} (JSON) carries no {@code refresh_token}, splice {@code priorRefreshToken}
   * into it so the stored raw keeps a usable token. A non-JSON body is returned unchanged.
   */
  private String mergeRefreshToken(String newBody, String priorRefreshToken) {
    try {
      JsonObject json = gson.fromJson(newBody, JsonObject.class);
      if (json == null) {
        return newBody;
      }
      String present = json.has("refresh_token") ? asString(json.get("refresh_token")) : null;
      if (present == null || present.isEmpty()) {
        json.addProperty("refresh_token", priorRefreshToken);
        return gson.toJson(json);
      }
      return newBody;
    } catch (JsonSyntaxException e) {
      return newBody;
    }
  }

  private boolean isInvalidGrant(@Nullable String body) {
    if (body == null) {
      return false;
    }
    try {
      JsonElement parsed = gson.fromJson(body, JsonElement.class);
      if (parsed != null && parsed.isJsonObject()) {
        return "invalid_grant".equals(asString(parsed.getAsJsonObject().get("error")));
      }
    } catch (JsonSyntaxException ignored) {
      // fall through
    }
    return false;
  }

  private boolean isInvalidToken(@Nullable String body) {
    if (body == null) {
      return false;
    }
    try {
      JsonElement parsed = gson.fromJson(body, JsonElement.class);
      if (parsed != null && parsed.isJsonObject()) {
        return "invalid_token".equals(asString(parsed.getAsJsonObject().get("error")));
      }
    } catch (JsonSyntaxException ignored) {
      // fall through
    }
    return false;
  }

  @Nullable
  private static Long asLong(@Nullable JsonElement e) {
    if (e == null || e.isJsonNull()) {
      return null;
    }
    try {
      return e.getAsLong();
    } catch (NumberFormatException | UnsupportedOperationException ex) {
      return null;
    }
  }

  @Nullable
  private static Long parseLongOrNull(@Nullable String s) {
    if (s == null || s.isEmpty()) {
      return null;
    }
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
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
