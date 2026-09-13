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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.gerrit.extensions.auth.oauth.OAuthAuthorizationInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthRevokedException;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import org.junit.Test;

public class HttpOAuthClientTest {
  private static final String CLIENT_ID = "gerrit-client";
  private static final String CLIENT_SECRET = "s3cr3t";
  private static final String CALLBACK = "https://gerrit.example.com/oauth";
  private static final String AUTHZ = "https://idp.example.com/authorize";
  private static final String TOKEN = "https://idp.example.com/token";
  private static final String REVOKE = "https://idp.example.com/revoke";

  /** Captures the last request the client made through the transport seam. */
  private static final class Captured {
    String method;
    String url;
    Map<String, String> headers;
    String body;
  }

  private static OAuthProviderEndpoints endpoints(
      String scope,
      ClientAuthStyle authStyle,
      BearerPlacement bearer,
      TokenResponseFormat format,
      boolean tolerateMissingTokenType,
      boolean enablePkce) {
    return new OAuthProviderEndpoints(
        AUTHZ, TOKEN, scope, authStyle, bearer, format, tolerateMissingTokenType, enablePkce);
  }

  private static OAuthProviderEndpoints standard(ClientAuthStyle authStyle) {
    return endpoints(
        "openid email",
        authStyle,
        BearerPlacement.AUTHORIZATION_HEADER,
        TokenResponseFormat.JSON,
        false,
        false);
  }

  /** A client whose transport is stubbed to record the request and return a canned response. */
  private static HttpOAuthClient client(
      OAuthProviderEndpoints ep, String secret, int code, String responseBody, Captured out) {
    return new HttpOAuthClient(ep, CLIENT_ID, secret, CALLBACK) {
      @Override
      OAuthHttpTransport.Response httpRequest(
          String method, String url, Map<String, String> headers, String body) {
        out.method = method;
        out.url = url;
        out.headers = headers;
        out.body = body;
        return new OAuthHttpTransport.Response(code, responseBody);
      }
    };
  }

  private static OAuthProviderEndpoints revokable() {
    return new OAuthProviderEndpoints(
        AUTHZ,
        TOKEN,
        "openid email",
        ClientAuthStyle.BASIC,
        BearerPlacement.AUTHORIZATION_HEADER,
        TokenResponseFormat.JSON,
        false,
        false,
        REVOKE);
  }

  @Test
  public void revoke_prefersRefreshToken_postsToRevocationEndpoint() throws Exception {
    Captured out = new Captured();
    OAuthToken token =
        new OAuthToken("access-x", "bearer", "{\"refresh_token\":\"rt-123\"}", 0, "p:x");

    client(revokable(), CLIENT_SECRET, 200, "", out).revoke(token);

    assertThat(out.method).isEqualTo("POST");
    assertThat(out.url).isEqualTo(REVOKE);
    assertThat(out.body).contains("token=rt-123");
    assertThat(out.body).contains("token_type_hint=refresh_token");
  }

  @Test
  public void revoke_noRefreshToken_fallsBackToAccessToken() throws Exception {
    Captured out = new Captured();
    OAuthToken token = new OAuthToken("access-x", "bearer", "{}", 0, "p:x");

    client(revokable(), CLIENT_SECRET, 200, "", out).revoke(token);

    assertThat(out.body).contains("token=access-x");
    assertThat(out.body).contains("token_type_hint=access_token");
  }

  @Test
  public void revoke_400InvalidToken_isNoOp() throws Exception {
    Captured out = new Captured();
    OAuthToken token = new OAuthToken("access-x", "bearer", "{}", 0, "p:x");

    // Google returns 400 invalid_token for an already-invalid token; tolerated as a no-op.
    client(revokable(), CLIENT_SECRET, 400, "{\"error\":\"invalid_token\"}", out).revoke(token);
  }

  @Test
  public void revoke_non2xx_throws() {
    Captured out = new Captured();
    OAuthToken token = new OAuthToken("access-x", "bearer", "{}", 0, "p:x");

    assertThrows(
        IOException.class,
        () -> client(revokable(), CLIENT_SECRET, 500, "boom", out).revoke(token));
  }

  @Test
  public void revoke_noRevocationEndpoint_throwsUnsupported() {
    Captured out = new Captured();
    OAuthToken token = new OAuthToken("access-x", "bearer", "{}", 0, "p:x");

    assertThrows(
        UnsupportedOperationException.class,
        () -> client(standard(ClientAuthStyle.BASIC), CLIENT_SECRET, 200, "", out).revoke(token));
  }

  @Test
  public void revoke_requestBodyAuth_includesClientCredentials() throws Exception {
    // Keycloak uses ClientAuthStyle.REQUEST_BODY: the revoke body must carry
    // client_id/client_secret.
    OAuthProviderEndpoints ep =
        new OAuthProviderEndpoints(
            AUTHZ,
            TOKEN,
            "openid",
            ClientAuthStyle.REQUEST_BODY,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            false,
            false,
            REVOKE);
    Captured out = new Captured();
    OAuthToken token = new OAuthToken("access-x", "bearer", "{}", 0, "p:x");

    client(ep, CLIENT_SECRET, 200, "", out).revoke(token);

    assertThat(out.body).contains("token=access-x");
    assertThat(out.body).contains("client_id=" + CLIENT_ID);
    assertThat(out.body).contains("client_secret=" + CLIENT_SECRET);
  }

  @Test
  public void supportsRevoke_reflectsRevocationEndpointPresence() {
    Captured out = new Captured();
    assertThat(client(revokable(), CLIENT_SECRET, 200, "", out).supportsRevoke()).isTrue();
    assertThat(
            client(standard(ClientAuthStyle.BASIC), CLIENT_SECRET, 200, "", out).supportsRevoke())
        .isFalse();
  }

  @Test
  public void authorizationUrl_noPkce_hasStandardShape() {
    OAuthAuthorizationInfo info =
        new HttpOAuthClient(standard(ClientAuthStyle.BASIC), CLIENT_ID, CLIENT_SECRET, CALLBACK)
            .getAuthorizationInfo();

    assertThat(info.getAuthorizationUrl())
        .isEqualTo(
            AUTHZ
                + "?response_type=code&client_id=gerrit-client"
                + "&redirect_uri=https%3A%2F%2Fgerrit.example.com%2Foauth"
                + "&scope=openid%20email");
    assertThat(info.getPkceVerifier()).isNull();
  }

  @Test
  public void authorizationUrl_nullScope_omitsScope() {
    OAuthProviderEndpoints ep =
        endpoints(
            null,
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            false,
            false);
    String url =
        new HttpOAuthClient(ep, CLIENT_ID, CLIENT_SECRET, CALLBACK)
            .getAuthorizationInfo()
            .getAuthorizationUrl();
    assertThat(url).doesNotContain("scope=");
  }

  @Test
  public void authorizationUrl_pkce_addsChallengeAndReturnsMatchingVerifier() throws Exception {
    OAuthProviderEndpoints ep =
        endpoints(
            "openid",
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            false,
            true);
    OAuthAuthorizationInfo info =
        new HttpOAuthClient(ep, CLIENT_ID, CLIENT_SECRET, CALLBACK).getAuthorizationInfo();

    String verifier = info.getPkceVerifier();
    assertThat(verifier).isNotNull();
    assertThat(info.getAuthorizationUrl()).contains("code_challenge_method=S256");
    // The challenge in the URL must be S256(verifier).
    String expectedChallenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(UTF_8)));
    assertThat(info.getAuthorizationUrl()).contains("code_challenge=" + expectedChallenge);
  }

  @Test
  public void exchangeCode_basicAuth_sendsHeaderAndFormBody() throws Exception {
    Captured c = new Captured();
    HttpOAuthClient client =
        client(
            standard(ClientAuthStyle.BASIC),
            CLIENT_SECRET,
            200,
            "{\"access_token\":\"at-123\",\"token_type\":\"bearer\"}",
            c);

    OAuthToken token = client.exchangeCode(new OAuthVerifier("auth-code"), null);

    assertThat(c.method).isEqualTo("POST");
    assertThat(c.url).isEqualTo(TOKEN);
    assertThat(c.headers).containsEntry("Content-Type", "application/x-www-form-urlencoded");
    assertThat(c.headers)
        .containsEntry(
            "Authorization",
            "Basic "
                + Base64.getEncoder()
                    .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(UTF_8)));
    assertThat(c.body)
        .isEqualTo(
            "code=auth-code"
                + "&redirect_uri=https%3A%2F%2Fgerrit.example.com%2Foauth"
                + "&scope=openid%20email"
                + "&grant_type=authorization_code");
    assertThat(token.getToken()).isEqualTo("at-123");
    assertThat(token.getRaw()).contains("at-123");
  }

  @Test
  public void exchangeCode_requestBodyAuth_putsCredentialsInBody() throws Exception {
    Captured c = new Captured();
    HttpOAuthClient client =
        client(
            standard(ClientAuthStyle.REQUEST_BODY),
            CLIENT_SECRET,
            200,
            "{\"access_token\":\"at\",\"token_type\":\"bearer\"}",
            c);

    client.exchangeCode(new OAuthVerifier("code"), null);

    assertThat(c.body).startsWith("client_id=gerrit-client&client_secret=s3cr3t&code=code");
    assertThat(c.headers).doesNotContainKey("Authorization");
  }

  @Test
  public void exchangeCode_pkce_appendsCodeVerifier() throws Exception {
    Captured c = new Captured();
    OAuthProviderEndpoints ep =
        endpoints(
            "openid",
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            false,
            true);
    client(ep, CLIENT_SECRET, 200, "{\"access_token\":\"at\",\"token_type\":\"bearer\"}", c)
        .exchangeCode(new OAuthVerifier("code"), "verifier-xyz");

    assertThat(c.body).endsWith("&grant_type=authorization_code&code_verifier=verifier-xyz");
  }

  @Test
  public void exchangeCode_pkceEnabled_missingVerifier_throws() {
    Captured c = new Captured();
    OAuthProviderEndpoints ep =
        endpoints(
            "openid",
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            false,
            true);
    HttpOAuthClient client =
        client(ep, CLIENT_SECRET, 200, "{\"access_token\":\"at\",\"token_type\":\"bearer\"}", c);
    assertThrows(IOException.class, () -> client.exchangeCode(new OAuthVerifier("code"), null));
  }

  @Test
  public void parseToken_formEncoded_extractsFields() throws Exception {
    Captured c = new Captured();
    OAuthProviderEndpoints ep =
        endpoints(
            "user",
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.FORM_URL_ENCODED,
            false,
            false);
    OAuthToken token =
        client(ep, CLIENT_SECRET, 200, "access_token=gho_abc&token_type=bearer&scope=user", c)
            .exchangeCode(new OAuthVerifier("code"), null);

    assertThat(token.getToken()).isEqualTo("gho_abc");
    assertThat(token.getSecret()).isEqualTo("bearer");
  }

  @Test
  public void parseToken_missingTokenType_toleratedAsEmpty() throws Exception {
    Captured c = new Captured();
    OAuthProviderEndpoints ep =
        endpoints(
            "user",
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            /* tolerateMissingTokenType= */ true,
            false);
    OAuthToken token =
        client(ep, CLIENT_SECRET, 200, "{\"access_token\":\"at\"}", c)
            .exchangeCode(new OAuthVerifier("code"), null);

    assertThat(token.getSecret()).isEmpty();
  }

  @Test
  public void exchangeCode_missingAccessToken_throws() {
    Captured c = new Captured();
    HttpOAuthClient client =
        client(standard(ClientAuthStyle.BASIC), CLIENT_SECRET, 200, "{\"scope\":\"x\"}", c);

    assertThrows(IOException.class, () -> client.exchangeCode(new OAuthVerifier("code"), null));
  }

  @Test
  public void exchangeCode_non2xx_throwsWithSafeError() {
    Captured c = new Captured();
    HttpOAuthClient client =
        client(
            standard(ClientAuthStyle.BASIC),
            CLIENT_SECRET,
            400,
            "{\"error\":\"invalid_grant\",\"error_description\":\"bad code\"}",
            c);

    IOException e =
        assertThrows(IOException.class, () -> client.exchangeCode(new OAuthVerifier("code"), null));
    assertThat(e).hasMessageThat().contains("invalid_grant");
  }

  @Test
  public void get_authorizationHeaderBearer() throws Exception {
    Captured c = new Captured();
    HttpOAuthClient client =
        client(standard(ClientAuthStyle.BASIC), CLIENT_SECRET, 200, "profile", c);

    String body = client.get(URI.create("https://api.example.com/me"), token("at-9"), Map.of());

    assertThat(body).isEqualTo("profile");
    assertThat(c.method).isEqualTo("GET");
    assertThat(c.url).isEqualTo("https://api.example.com/me");
    assertThat(c.headers).containsEntry("Authorization", "Bearer at-9");
  }

  @Test
  public void get_uriQueryBearer_appendsAccessTokenParam() throws Exception {
    Captured c = new Captured();
    OAuthProviderEndpoints ep =
        endpoints(
            "user",
            ClientAuthStyle.BASIC,
            BearerPlacement.URI_QUERY_ACCESS_TOKEN,
            TokenResponseFormat.JSON,
            false,
            false);
    client(ep, CLIENT_SECRET, 200, "u", c)
        .get(URI.create("https://api.example.com/user"), token("at-9"), Map.of());

    assertThat(c.url).isEqualTo("https://api.example.com/user?access_token=at-9");
    assertThat(c.headers).doesNotContainKey("Authorization");
  }

  @Test
  public void get_passesExtraHeaders() throws Exception {
    Captured c = new Captured();
    client(standard(ClientAuthStyle.BASIC), CLIENT_SECRET, 200, "ok", c)
        .get(URI.create("https://api.example.com/me"), token("at"), Map.of("Accept", "*/*"));

    assertThat(c.headers).containsEntry("Accept", "*/*");
  }

  @Test
  public void get_non200_throws() {
    Captured c = new Captured();
    HttpOAuthClient client = client(standard(ClientAuthStyle.BASIC), CLIENT_SECRET, 401, "nope", c);

    assertThrows(
        IOException.class,
        () -> client.get(URI.create("https://api.example.com/me"), token("at"), Map.of()));
  }

  @Test
  public void version_is20() {
    assertThat(
            new HttpOAuthClient(standard(ClientAuthStyle.BASIC), CLIENT_ID, CLIENT_SECRET, CALLBACK)
                .getVersion())
        .isEqualTo("2.0");
  }

  @Test
  public void passwordGrant_sendsPasswordGrantBody() throws Exception {
    Captured c = new Captured();
    OAuthToken token =
        client(
                standard(ClientAuthStyle.BASIC),
                CLIENT_SECRET,
                200,
                "{\"access_token\":\"at\",\"token_type\":\"bearer\"}",
                c)
            .passwordGrant("alice", "pw");

    assertThat(c.method).isEqualTo("POST");
    // Password-grant body order: username, password, scope, grant_type (request-body client auth
    // would be last; here Basic auth puts credentials in the header instead).
    assertThat(c.body)
        .isEqualTo("username=alice&password=pw&scope=openid%20email&grant_type=password");
    assertThat(token.getToken()).isEqualTo("at");
  }

  @Test
  public void emptyScope_normalizedToNull_omitsScope() throws Exception {
    OAuthProviderEndpoints ep =
        endpoints(
            "",
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.JSON,
            false,
            false);
    assertThat(ep.scope()).isNull();

    Captured c = new Captured();
    HttpOAuthClient client =
        client(ep, CLIENT_SECRET, 200, "{\"access_token\":\"at\",\"token_type\":\"bearer\"}", c);

    assertThat(client.getAuthorizationInfo().getAuthorizationUrl()).doesNotContain("scope=");
    client.exchangeCode(new OAuthVerifier("code"), null);
    assertThat(c.body).doesNotContain("scope=");
  }

  @Test
  public void passwordGrant_requestBodyAuth_appendsCredentialsLast() throws Exception {
    Captured c = new Captured();
    client(
            standard(ClientAuthStyle.REQUEST_BODY),
            CLIENT_SECRET,
            200,
            "{\"access_token\":\"at\",\"token_type\":\"bearer\"}",
            c)
        .passwordGrant("alice", "pw");

    assertThat(c.body)
        .isEqualTo(
            "username=alice&password=pw&scope=openid%20email&grant_type=password"
                + "&client_id=gerrit-client&client_secret=s3cr3t");
  }

  @Test
  public void parseToken_formEmptyAccessToken_throws() {
    Captured c = new Captured();
    OAuthProviderEndpoints ep =
        endpoints(
            "user",
            ClientAuthStyle.BASIC,
            BearerPlacement.AUTHORIZATION_HEADER,
            TokenResponseFormat.FORM_URL_ENCODED,
            false,
            false);
    HttpOAuthClient client = client(ep, CLIENT_SECRET, 200, "access_token=&token_type=bearer", c);

    assertThrows(IOException.class, () -> client.exchangeCode(new OAuthVerifier("code"), null));
  }

  @Test
  public void constructor_blankClientIdOrSecret_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpOAuthClient(standard(ClientAuthStyle.BASIC), "", CLIENT_SECRET, CALLBACK));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpOAuthClient(standard(ClientAuthStyle.BASIC), CLIENT_ID, "  ", CALLBACK));
  }

  private static OAuthToken token(String accessToken) {
    return new OAuthToken(accessToken, "bearer", "{}");
  }

  @Test
  public void parseToken_stampsProviderId() throws Exception {
    HttpOAuthClient client =
        new HttpOAuthClient(
            standard(ClientAuthStyle.BASIC),
            CLIENT_ID,
            CLIENT_SECRET,
            CALLBACK,
            "gerrit-oauth-provider:google-oauth") {
          @Override
          OAuthHttpTransport.Response httpRequest(
              String method, String url, Map<String, String> headers, String body) {
            return new OAuthHttpTransport.Response(
                200, "{\"access_token\":\"at\",\"token_type\":\"bearer\"}");
          }
        };

    OAuthToken token = client.exchangeCode(new OAuthVerifier("code"), null);

    assertThat(token.getProviderId()).isEqualTo("gerrit-oauth-provider:google-oauth");
  }

  @Test
  public void parseToken_populatesExpiresAtFromExpiresIn() throws Exception {
    Captured c = new Captured();
    HttpOAuthClient client =
        client(
            standard(ClientAuthStyle.BASIC),
            CLIENT_SECRET,
            200,
            "{\"access_token\":\"at-1\",\"token_type\":\"bearer\",\"expires_in\":3600}",
            c);

    long before = System.currentTimeMillis();
    OAuthToken t = client.exchangeCode(new OAuthVerifier("code"), null);

    assertThat(t.getExpiresAt()).isNotEqualTo(Long.MAX_VALUE);
    assertThat(t.getExpiresAt()).isAtLeast(before + 3590_000L);
    assertThat(t.getExpiresAt()).isAtMost(System.currentTimeMillis() + 3600_000L);
  }

  @Test
  public void parseToken_noExpiresIn_leavesExpiresAtUnknown() throws Exception {
    Captured c = new Captured();
    HttpOAuthClient client =
        client(
            standard(ClientAuthStyle.BASIC),
            CLIENT_SECRET,
            200,
            "{\"access_token\":\"at-1\",\"token_type\":\"bearer\"}",
            c);

    OAuthToken t = client.exchangeCode(new OAuthVerifier("code"), null);

    assertThat(t.getExpiresAt()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void refresh_sendsRefreshGrant_reusesClientAuthStyle_populatesExpiry() throws Exception {
    Captured c = new Captured();
    HttpOAuthClient client =
        client(
            standard(ClientAuthStyle.BASIC),
            CLIENT_SECRET,
            200,
            "{\"access_token\":\"at-new\",\"token_type\":\"bearer\",\"expires_in\":3600}",
            c);

    OAuthToken refreshed =
        client.refresh(
            new OAuthToken(
                "at-old",
                "bearer",
                "{\"access_token\":\"at-old\",\"refresh_token\":\"r-1\"}",
                0L,
                "gerrit-oauth-provider:test-oauth"));

    assertThat(c.method).isEqualTo("POST");
    assertThat(c.url).isEqualTo(TOKEN);
    assertThat(c.body).contains("grant_type=refresh_token");
    assertThat(c.body).contains("refresh_token=r-1");
    // Basic client auth: credentials in the header, never the body.
    assertThat(c.headers).containsKey("Authorization");
    assertThat(c.body).doesNotContain("client_secret=");
    assertThat(refreshed.getToken()).isEqualTo("at-new");
    assertThat(refreshed.getExpiresAt()).isNotEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void refresh_responseOmitsRefreshToken_mergesPriorOne() throws Exception {
    Captured c = new Captured();
    HttpOAuthClient client =
        client(
            standard(ClientAuthStyle.BASIC),
            CLIENT_SECRET,
            200,
            // Google commonly omits refresh_token on refresh.
            "{\"access_token\":\"at-new\",\"token_type\":\"bearer\",\"expires_in\":3600}",
            c);

    OAuthToken refreshed =
        client.refresh(
            new OAuthToken("at-old", "bearer", "{\"refresh_token\":\"r-keep\"}", 0L, "p:e-oauth"));

    // The prior refresh token is carried forward into the new raw (raw-merge).
    assertThat(refreshed.getRaw()).contains("r-keep");
    assertThat(refreshed.getRaw()).contains("refresh_token");
  }

  @Test
  public void refresh_invalidGrant_throwsOAuthRevoked() {
    Captured c = new Captured();
    HttpOAuthClient client =
        client(
            standard(ClientAuthStyle.BASIC),
            CLIENT_SECRET,
            400,
            "{\"error\":\"invalid_grant\"}",
            c);

    assertThrows(
        OAuthRevokedException.class,
        () ->
            client.refresh(
                new OAuthToken("at", "bearer", "{\"refresh_token\":\"r-1\"}", 0L, "p:e-oauth")));
  }

  @Test
  public void refresh_missingRefreshToken_throwsIOExceptionNotRevoked() {
    Captured c = new Captured();
    HttpOAuthClient client = client(standard(ClientAuthStyle.BASIC), CLIENT_SECRET, 200, "{}", c);

    IOException e =
        assertThrows(
            IOException.class,
            () -> client.refresh(new OAuthToken("at", "bearer", "{}", 0L, "p:e-oauth")));
    assertThat(e).isNotInstanceOf(OAuthRevokedException.class);
  }

  @Test
  public void refresh_transientError_throwsIOExceptionNotRevoked() {
    Captured c = new Captured();
    HttpOAuthClient client = client(standard(ClientAuthStyle.BASIC), CLIENT_SECRET, 500, "oops", c);

    IOException e =
        assertThrows(
            IOException.class,
            () ->
                client.refresh(
                    new OAuthToken(
                        "at", "bearer", "{\"refresh_token\":\"r-1\"}", 0L, "p:e-oauth")));
    assertThat(e).isNotInstanceOf(OAuthRevokedException.class);
  }
}
