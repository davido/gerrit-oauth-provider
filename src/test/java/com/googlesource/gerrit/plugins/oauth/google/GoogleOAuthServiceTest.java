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

package com.googlesource.gerrit.plugins.oauth.google;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthAuthorizationInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.oauth.base.HttpOAuthClientFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.client.BearerPlacement;
import com.googlesource.gerrit.plugins.oauth.client.ClientAuthStyle;
import com.googlesource.gerrit.plugins.oauth.client.OAuthClient;
import com.googlesource.gerrit.plugins.oauth.client.OAuthProviderEndpoints;
import com.googlesource.gerrit.plugins.oauth.client.TokenResponseFormat;
import com.googlesource.gerrit.plugins.oauth.jwt.OidcJwtValidator;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GoogleOAuthServiceTest {
  private static final String ISSUER = "https://accounts.google.com";
  private static final String AUDIENCE = "gerrit-client.apps.googleusercontent.com";

  private static RSAKey rsaKey;
  private static JWKSource<SecurityContext> jwks;

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private HttpOAuthClientFactory mockServiceFactory;
  @Mock private OAuthClient mockClient;

  @BeforeClass
  public static void generateKey() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("primary").generate();
    jwks = new ImmutableJWKSet<>(new JWKSet(List.of(rsaKey.toPublicJWK())));
  }

  @Before
  public void setUp() {
    when(mockConfigFactory.create(GoogleOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getStringList(OAuthConfigKeys.DOMAIN)).thenReturn(new String[0]);
    when(mockServiceFactory.create(anyString(), any(OAuthProviderEndpoints.class)))
        .thenReturn(mockClient);
  }

  @Test
  public void getUserInfo_validIdTokenMatchingUserinfo_mapsUserInfo() throws Exception {
    when(mockClient.get(any(URI.class), any(OAuthToken.class)))
        .thenReturn("{\"id\":\"12345\",\"email\":\"jane@example.com\",\"name\":\"Jane\"}");

    OAuthUserInfo userInfo = service().getUserInfo(tokenWith(sign(claims().build())));

    assertThat(userInfo.getExternalId()).isEqualTo("google-oauth:12345");
    assertThat(userInfo.getEmailAddress()).isEqualTo("jane@example.com");
    assertThat(userInfo.getDisplayName()).isEqualTo("Jane");
  }

  @Test
  public void getUserInfo_userinfoIdMismatch_rejected() throws Exception {
    // id_token sub=12345 but userinfo id=99999: token substitution, must be rejected.
    when(mockClient.get(any(URI.class), any(OAuthToken.class)))
        .thenReturn("{\"id\":\"99999\",\"email\":\"e@example.com\",\"name\":\"N\"}");

    assertThrows(IOException.class, () -> service().getUserInfo(tokenWith(sign(claims().build()))));
  }

  @Test
  public void getUserInfo_wrongAudienceIdToken_rejectedBeforeUserinfo() throws Exception {
    OAuthToken token = tokenWith(sign(claims().audience("some-other-client").build()));

    assertThrows(IOException.class, () -> service().getUserInfo(token));
    verify(mockClient, never()).get(any(URI.class), any(OAuthToken.class));
  }

  @Test
  public void getUserInfo_missingIdToken_rejectedBeforeUserinfo() throws Exception {
    OAuthToken token = new OAuthToken("access", "Bearer", "{}");

    assertThrows(IOException.class, () -> service().getUserInfo(token));
    verify(mockClient, never()).get(any(URI.class), any(OAuthToken.class));
  }

  @Test
  public void getUserInfo_hostedDomainMismatch_returnsNull() throws Exception {
    when(mockPluginConfig.getStringList(OAuthConfigKeys.DOMAIN))
        .thenReturn(new String[] {"example.com"});
    when(mockClient.get(any(URI.class), any(OAuthToken.class)))
        .thenReturn("{\"id\":\"12345\",\"email\":\"jane@other.com\",\"name\":\"Jane\"}");
    // id_token verified, sub matches userinfo id, but hd is a different domain.
    String jwt = sign(claims().claim("hd", "other.com").build());

    assertThat(service().getUserInfo(tokenWith(jwt))).isNull();
  }

  @Test
  public void constructor_buildsGoogleDescriptor() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    service();

    OAuthProviderEndpoints ep = capturedEndpoints();
    assertThat(ep.authorizationEndpoint()).isEqualTo("https://accounts.google.com/o/oauth2/auth");
    assertThat(ep.tokenEndpoint()).isEqualTo("https://www.googleapis.com/oauth2/v4/token");
    assertThat(ep.scope()).isEqualTo("openid email profile");
    assertThat(ep.clientAuthStyle()).isEqualTo(ClientAuthStyle.BASIC);
    assertThat(ep.bearerPlacement()).isEqualTo(BearerPlacement.AUTHORIZATION_HEADER);
    assertThat(ep.tokenResponseFormat()).isEqualTo(TokenResponseFormat.JSON);
    assertThat(ep.tolerateMissingTokenType()).isFalse();
    assertThat(ep.enablePkce()).isTrue();
  }

  @Test
  public void constructor_pkceDisabledByDefault() {
    service();

    assertThat(capturedEndpoints().enablePkce()).isFalse();
  }

  @Test
  public void getAuthorizationInfo_noDomain_leavesUrlUnchanged() {
    when(mockClient.getAuthorizationInfo())
        .thenReturn(
            new OAuthAuthorizationInfo("https://accounts.google.com/o/oauth2/auth?x=y", "v"));

    OAuthAuthorizationInfo info = service().getAuthorizationInfo();

    assertThat(info.getAuthorizationUrl())
        .isEqualTo("https://accounts.google.com/o/oauth2/auth?x=y");
    assertThat(info.getPkceVerifier()).isEqualTo("v");
  }

  @Test
  public void getAuthorizationInfo_singleDomain_appendsEncodedHd() {
    when(mockPluginConfig.getStringList(OAuthConfigKeys.DOMAIN))
        .thenReturn(new String[] {"a b.com"});
    when(mockClient.getAuthorizationInfo())
        .thenReturn(
            new OAuthAuthorizationInfo("https://accounts.google.com/o/oauth2/auth?x=y", "v"));

    OAuthAuthorizationInfo info = service().getAuthorizationInfo();

    assertThat(info.getAuthorizationUrl())
        .isEqualTo("https://accounts.google.com/o/oauth2/auth?x=y&hd=a+b.com");
  }

  @Test
  public void getAuthorizationInfo_multipleDomains_appendsWildcardHd() {
    when(mockPluginConfig.getStringList(OAuthConfigKeys.DOMAIN))
        .thenReturn(new String[] {"a.com", "b.com"});
    when(mockClient.getAuthorizationInfo())
        .thenReturn(
            new OAuthAuthorizationInfo("https://accounts.google.com/o/oauth2/auth?x=y", "v"));

    OAuthAuthorizationInfo info = service().getAuthorizationInfo();

    assertThat(info.getAuthorizationUrl())
        .isEqualTo("https://accounts.google.com/o/oauth2/auth?x=y&hd=*");
  }

  @Test
  public void refreshDisabledByDefault() {
    assertThat(service().supportsRefresh()).isFalse();
  }

  @Test
  public void refreshEnabled_appendsAccessTypeOffline_andSupportsRefresh() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_TOKEN_REFRESH, false)).thenReturn(true);
    when(mockClient.getAuthorizationInfo())
        .thenReturn(
            new OAuthAuthorizationInfo("https://accounts.google.com/o/oauth2/auth?x=y", "v"));

    GoogleOAuthService svc = service();

    assertThat(svc.supportsRefresh()).isTrue();
    assertThat(svc.getAuthorizationInfo().getAuthorizationUrl())
        .isEqualTo("https://accounts.google.com/o/oauth2/auth?x=y&access_type=offline");
  }

  @Test
  public void forceConsent_appendsPromptConsent() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_TOKEN_REFRESH, false)).thenReturn(true);
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.FORCE_CONSENT, false)).thenReturn(true);
    when(mockClient.getAuthorizationInfo())
        .thenReturn(
            new OAuthAuthorizationInfo("https://accounts.google.com/o/oauth2/auth?x=y", "v"));

    assertThat(service().getAuthorizationInfo().getAuthorizationUrl())
        .isEqualTo(
            "https://accounts.google.com/o/oauth2/auth?x=y&access_type=offline&prompt=consent");
  }

  @Test
  public void refresh_delegatesToClient() throws Exception {
    OAuthToken expired = new OAuthToken("a", "s", "{}", 1L, "google-oauth:google");
    OAuthToken refreshed = new OAuthToken("b", "s", "{}", Long.MAX_VALUE, "google-oauth:google");
    when(mockClient.refresh(expired)).thenReturn(refreshed);

    assertThat(service().refresh(expired)).isEqualTo(refreshed);
    verify(mockClient).refresh(expired);
  }

  private OAuthProviderEndpoints capturedEndpoints() {
    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockServiceFactory).create(eq(GoogleOAuthService.PROVIDER_NAME), captor.capture());
    return captor.getValue();
  }

  private GoogleOAuthService service() {
    return new GoogleOAuthService(mockConfigFactory, mockServiceFactory, testValidator());
  }

  private static OidcJwtValidator testValidator() {
    return OidcJwtValidator.builder().issuer(ISSUER).audience(AUDIENCE).jwkSource(jwks).build();
  }

  private static JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .subject("12345")
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plusSeconds(300)));
  }

  private static String sign(JWTClaimsSet claimSet) throws Exception {
    JWSSigner signer = new RSASSASigner(rsaKey);
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(rsaKey.getKeyID())
                .build(),
            claimSet);
    signed.sign(signer);
    return signed.serialize();
  }

  private static OAuthToken tokenWith(String idToken) {
    JsonObject raw = new JsonObject();
    raw.addProperty("id_token", idToken);
    return new OAuthToken("access", "Bearer", raw.toString());
  }
}
