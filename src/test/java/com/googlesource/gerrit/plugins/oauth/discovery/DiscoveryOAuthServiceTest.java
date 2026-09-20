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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthAuthorizationInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonObject;
import com.google.inject.ProvisionException;
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
public class DiscoveryOAuthServiceTest {
  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private OAuthClient mockClient;
  @Mock private HttpOAuthClientFactory mockServiceFactory;

  private static final String TEST_DISCOVERY_ROOT_URL = "https://id.example.com/realms/gerrit";
  private static final String TEST_ISSUER = "https://id.example.com/realms/gerrit";
  private static final String TEST_AUTHORIZATION_ENDPOINT =
      "https://id.example.com/realms/gerrit/protocol/openid-connect/auth";
  private static final String TEST_TOKEN_ENDPOINT =
      "https://id.example.com/realms/gerrit/protocol/openid-connect/token";
  private static final String TEST_USERINFO_ENDPOINT =
      "https://id.example.com/realms/gerrit/protocol/openid-connect/userinfo";

  private static final String DISCOVERY_PROVIDER_PREFIX_FOR_TEST = "discovery-oauth:";
  private static final String CLIENT_ID = "gerrit-client";

  private static RSAKey rsaKey;
  private static JWKSource<SecurityContext> jwks;

  @BeforeClass
  public static void generateKey() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("primary").generate();
    jwks = new ImmutableJWKSet<>(new JWKSet(List.of(rsaKey.toPublicJWK())));
  }

  @Before
  public void setUp() {
    when(mockConfigFactory.create(DiscoveryOAuthService.PROVIDER_NAME))
        .thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL)).thenReturn(TEST_DISCOVERY_ROOT_URL);

    when(mockServiceFactory.create(anyString(), any(OAuthProviderEndpoints.class)))
        .thenReturn(mockClient);
  }

  private DiscoveryOpenIdConnect mockDiscoveryDocument(
      String issuer, String authorizationEndpoint, String tokenEndpoint, String userinfoEndpoint) {
    DiscoveryOpenIdConnect discovery = mock(DiscoveryOpenIdConnect.class);
    when(discovery.getIssuer()).thenReturn(issuer);
    when(discovery.getAuthorizationEndpoint()).thenReturn(authorizationEndpoint);
    when(discovery.getTokenEndpoint()).thenReturn(tokenEndpoint);
    when(discovery.getUserinfoEndpoint()).thenReturn(userinfoEndpoint);
    return discovery;
  }

  private DiscoveryOpenIdConnect validDiscoveryDocument() {
    return mockDiscoveryDocument(
        TEST_ISSUER, TEST_AUTHORIZATION_ENDPOINT, TEST_TOKEN_ENDPOINT, TEST_USERINFO_ENDPOINT);
  }

  private DiscoveryOAuthService createServiceWithDiscoveryDoc(DiscoveryOpenIdConnect discovery) {
    return new DiscoveryOAuthService(mockConfigFactory, mockServiceFactory) {
      @Override
      DiscoveryOpenIdConnect fetchDiscoveryDocument(String discoveryUrl) {
        return discovery;
      }
    };
  }

  private ProvisionException assertProvisionException(DiscoveryOpenIdConnect discovery) {
    try {
      createServiceWithDiscoveryDoc(discovery);
    } catch (ProvisionException e) {
      return e;
    }
    throw new AssertionError("expected ProvisionException");
  }

  private ProvisionException assertConstructorProvisionException() {
    try {
      new DiscoveryOAuthService(mockConfigFactory, mockServiceFactory);
    } catch (ProvisionException e) {
      return e;
    }
    throw new AssertionError("expected ProvisionException");
  }

  private void mockUserInfoResponse(String body) throws Exception {
    when(mockClient.get(any(URI.class), any(OAuthToken.class))).thenReturn(body);
  }

  private DiscoveryOAuthService createServiceWithValidator(OidcJwtValidator validator) {
    return new DiscoveryOAuthService(mockConfigFactory, mockServiceFactory, validator) {
      @Override
      DiscoveryOpenIdConnect fetchDiscoveryDocument(String discoveryUrl) {
        return validDiscoveryDocument();
      }
    };
  }

  @Test
  public void getUserInfo_validIdToken_validatesThenMapsUserInfo() throws Exception {
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    mockUserInfoResponse(
        "{\"sub\":\"12345\",\"preferred_username\":\"jane.doe\","
            + "\"email\":\"jane.doe@example.com\",\"name\":\"Jane Doe\"}");

    OAuthUserInfo userInfo = service.getUserInfo(idTokenResponse(sign(claims().build())));

    assertThat(userInfo.getExternalId()).isEqualTo(DISCOVERY_PROVIDER_PREFIX_FOR_TEST + "12345");
    assertThat(userInfo.getUserName()).isEqualTo("jane.doe");
  }

  @Test
  public void getUserInfo_configuredExternalIdScheme_usesIt() throws Exception {
    // Migration knob: keep a deprecated wrapper's scheme (e.g. auth0-oauth) so accounts stay
    // linked.
    when(mockPluginConfig.getString(OAuthConfigKeys.EXTERNAL_ID_SCHEME)).thenReturn("auth0-oauth");
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    mockUserInfoResponse("{\"sub\":\"12345\",\"email\":\"jane.doe@example.com\"}");

    OAuthUserInfo userInfo = service.getUserInfo(idTokenResponse(sign(claims().build())));

    assertThat(userInfo.getExternalId()).isEqualTo("auth0-oauth:12345");
  }

  @Test
  public void getUserInfo_blankExternalIdScheme_usesDefault() throws Exception {
    when(mockPluginConfig.getString(OAuthConfigKeys.EXTERNAL_ID_SCHEME)).thenReturn("   ");
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    mockUserInfoResponse("{\"sub\":\"12345\",\"email\":\"jane.doe@example.com\"}");

    OAuthUserInfo userInfo = service.getUserInfo(idTokenResponse(sign(claims().build())));

    assertThat(userInfo.getExternalId()).isEqualTo(DISCOVERY_PROVIDER_PREFIX_FOR_TEST + "12345");
  }

  @Test
  public void getUserInfo_linkToExistingGerrit_setsClaimedIdentity() throws Exception {
    // link-to-existing-gerrit-accounts makes the browser flow emit gerrit:<username> as claimed
    // identity, matching the Authentik/Cognito wrappers.
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.LINK_TO_EXISTING_GERRIT_ACCOUNT, false))
        .thenReturn(true);
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    mockUserInfoResponse("{\"sub\":\"12345\",\"preferred_username\":\"jane.doe\"}");

    OAuthUserInfo userInfo = service.getUserInfo(idTokenResponse(sign(claims().build())));

    assertThat(userInfo.getClaimedIdentity()).isEqualTo("gerrit:jane.doe");
  }

  @Test
  public void getUserInfo_linkToExisting_missingUsername_failsClosed() throws Exception {
    // link enabled but no username -> fail closed, so linking is never silently skipped.
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.LINK_TO_EXISTING_GERRIT_ACCOUNT, false))
        .thenReturn(true);
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    mockUserInfoResponse("{\"sub\":\"12345\",\"email\":\"jane@example.com\"}");

    assertThrows(
        IOException.class, () -> service.getUserInfo(idTokenResponse(sign(claims().build()))));
  }

  @Test
  public void getUserInfo_linkToExisting_blankUsername_failsClosed() throws Exception {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.LINK_TO_EXISTING_GERRIT_ACCOUNT, false))
        .thenReturn(true);
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    mockUserInfoResponse("{\"sub\":\"12345\",\"preferred_username\":\"   \"}");

    assertThrows(
        IOException.class, () -> service.getUserInfo(idTokenResponse(sign(claims().build()))));
  }

  @Test
  public void getUserInfo_default_noClaimedIdentity() throws Exception {
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    mockUserInfoResponse("{\"sub\":\"12345\",\"preferred_username\":\"jane.doe\"}");

    OAuthUserInfo userInfo = service.getUserInfo(idTokenResponse(sign(claims().build())));

    assertThat(userInfo.getClaimedIdentity()).isNull();
  }

  @Test
  public void constructor_dexOauthScheme_rejected_needsClaimKnob() {
    // Dex maps its external id from email, not sub, so scheme preservation alone would still relink
    // accounts (dex-oauth:<sub> != the existing dex-oauth:<email>). Excluded until a claim knob.
    when(mockPluginConfig.getString(OAuthConfigKeys.EXTERNAL_ID_SCHEME)).thenReturn("dex-oauth");

    assertThrows(
        ProvisionException.class, () -> createServiceWithDiscoveryDoc(validDiscoveryDocument()));
  }

  @Test
  public void constructor_buildsDiscoveryDescriptor() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    createServiceWithDiscoveryDoc(validDiscoveryDocument());

    OAuthProviderEndpoints ep = capturedEndpoints();
    assertThat(ep.authorizationEndpoint()).isEqualTo(TEST_AUTHORIZATION_ENDPOINT);
    assertThat(ep.tokenEndpoint()).isEqualTo(TEST_TOKEN_ENDPOINT);
    assertThat(ep.scope()).isEqualTo("openid profile email");
    assertThat(ep.clientAuthStyle()).isEqualTo(ClientAuthStyle.BASIC);
    assertThat(ep.bearerPlacement()).isEqualTo(BearerPlacement.AUTHORIZATION_HEADER);
    assertThat(ep.tokenResponseFormat()).isEqualTo(TokenResponseFormat.JSON);
    assertThat(ep.tolerateMissingTokenType()).isFalse();
    assertThat(ep.enablePkce()).isTrue();
  }

  @Test
  public void refreshDisabledByDefault() {
    assertThat(createServiceWithDiscoveryDoc(validDiscoveryDocument()).supportsRefresh()).isFalse();
  }

  @Test
  public void refreshEnabled_supportsRefresh() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_TOKEN_REFRESH, false)).thenReturn(true);

    assertThat(createServiceWithDiscoveryDoc(validDiscoveryDocument()).supportsRefresh()).isTrue();
  }

  @Test
  public void refresh_delegatesToClient() throws Exception {
    OAuthToken expired = new OAuthToken("a", "s", "{}", 1L, "discovery-oauth:discovery");
    OAuthToken refreshed =
        new OAuthToken("b", "s", "{}", Long.MAX_VALUE, "discovery-oauth:discovery");
    when(mockClient.refresh(expired)).thenReturn(refreshed);

    assertThat(createServiceWithDiscoveryDoc(validDiscoveryDocument()).refresh(expired))
        .isEqualTo(refreshed);
    verify(mockClient).refresh(expired);
  }

  @Test
  public void constructor_defaultClientAuth_usesBasic() {
    createServiceWithDiscoveryDoc(validDiscoveryDocument());

    assertThat(capturedEndpoints().clientAuthStyle()).isEqualTo(ClientAuthStyle.BASIC);
  }

  @Test
  public void constructor_requestBodyClientAuth_usesRequestBody() {
    // Migration knob: a wrapper whose IdP only accepts request-body client auth (e.g. LemonLDAP)
    // can move onto Discovery without changing the IdP.
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_AUTH_METHOD)).thenReturn("request-body");

    createServiceWithDiscoveryDoc(validDiscoveryDocument());

    assertThat(capturedEndpoints().clientAuthStyle()).isEqualTo(ClientAuthStyle.REQUEST_BODY);
  }

  @Test
  public void constructor_invalidClientAuth_throwsProvisionException() {
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_AUTH_METHOD)).thenReturn("mtls");

    assertThrows(
        ProvisionException.class, () -> createServiceWithDiscoveryDoc(validDiscoveryDocument()));
  }

  private OAuthProviderEndpoints capturedEndpoints() {
    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockServiceFactory).create(anyString(), captor.capture());
    return captor.getValue();
  }

  @Test
  public void constructor_disallowedExternalIdScheme_throwsProvisionException() {
    // Clean syntax but not in the allowlist -> rejected, since the scheme determines account
    // identity (a syntax-only check would have accepted this).
    when(mockPluginConfig.getString(OAuthConfigKeys.EXTERNAL_ID_SCHEME)).thenReturn("okta-oauth");

    assertThrows(
        ProvisionException.class, () -> createServiceWithDiscoveryDoc(validDiscoveryDocument()));
  }

  @Test
  public void getUserInfo_invalidIdToken_rejectsBeforeFetchingUserInfo() throws Exception {
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    // Wrong issuer -> the id_token check fails, so the userinfo endpoint is never called.
    OAuthToken token = idTokenResponse(sign(claims().issuer("https://attacker.example/").build()));

    assertThrows(IOException.class, () -> service.getUserInfo(token));
    verify(mockClient, never()).get(any(URI.class), any(OAuthToken.class));
  }

  @Test
  public void getUserInfo_userinfoSubjectMismatch_rejected() throws Exception {
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    // Valid id_token (sub=12345) but the userinfo endpoint returns a different subject.
    mockUserInfoResponse(
        "{\"sub\":\"99999\",\"preferred_username\":\"admin\",\"email\":\"a@example.com\","
            + "\"name\":\"Admin\"}");

    assertThrows(
        IOException.class, () -> service.getUserInfo(idTokenResponse(sign(claims().build()))));
  }

  @Test
  public void getUserInfo_idTokenWithoutSubject_rejected() throws Exception {
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    // Validly signed id_token with no sub claim must be rejected, not treated as an unbound
    // subject.
    JWTClaimsSet noSub =
        new JWTClaimsSet.Builder()
            .issuer(TEST_ISSUER)
            .audience(CLIENT_ID)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();

    assertThrows(IOException.class, () -> service.getUserInfo(idTokenResponse(sign(noSub))));
    verify(mockClient, never()).get(any(URI.class), any(OAuthToken.class));
  }

  @Test
  public void getUserInfo_missingIdToken_throwsIOException() throws Exception {
    DiscoveryOAuthService service = createServiceWithValidator(testValidator());
    OAuthToken token = new OAuthToken("access", "Bearer", "{}");

    assertThrows(IOException.class, () -> service.getUserInfo(token));
    verify(mockClient, never()).get(any(URI.class), any(OAuthToken.class));
  }

  private static OidcJwtValidator testValidator() {
    return OidcJwtValidator.builder()
        .issuer(TEST_ISSUER)
        .audience(CLIENT_ID)
        .jwkSource(jwks)
        .build();
  }

  private static JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .subject("12345")
        .issuer(TEST_ISSUER)
        .audience(CLIENT_ID)
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

  private static OAuthToken idTokenResponse(String jwt) {
    JsonObject raw = new JsonObject();
    raw.addProperty("id_token", jwt);
    return new OAuthToken("access", "Bearer", raw.toString());
  }

  @Test
  public void getAuthorizationInfo_withPkceEnabled_shouldDelegateAndEnablePkce() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    OAuthAuthorizationInfo expected =
        new OAuthAuthorizationInfo(
            "https://id.example.com/auth?code_challenge=xyz", "secret-verifier-123");
    when(mockClient.getAuthorizationInfo()).thenReturn(expected);

    DiscoveryOAuthService service = createServiceWithDiscoveryDoc(validDiscoveryDocument());
    OAuthAuthorizationInfo info = service.getAuthorizationInfo();

    assertThat(info.getAuthorizationUrl()).contains("code_challenge=xyz");
    assertThat(info.getPkceVerifier()).isEqualTo("secret-verifier-123");

    // The provider must have created a PKCE-enabled client.
    assertThat(capturedEndpoints().enablePkce()).isTrue();
  }

  @Test
  public void getAccessToken_withPkce_shouldDelegateVerifierToClient() throws Exception {
    DiscoveryOAuthService service = createServiceWithDiscoveryDoc(validDiscoveryDocument());

    OAuthVerifier verifier = new OAuthVerifier("auth-code");
    String secureVerifierFromSession = "session-secret-verifier";
    OAuthToken expected = new OAuthToken("dummy-access-token", "Bearer", "raw-json-response");
    when(mockClient.exchangeCode(verifier, secureVerifierFromSession)).thenReturn(expected);

    OAuthToken result = service.getAccessToken(verifier, secureVerifierFromSession);

    assertThat(result).isSameInstanceAs(expected);
    verify(mockClient).exchangeCode(verifier, secureVerifierFromSession);
  }

  @Test
  public void getUserInfo_validStandardFields_shouldMapUserInfo() throws Exception {
    DiscoveryOAuthService service = createServiceWithDiscoveryDoc(validDiscoveryDocument());

    mockUserInfoResponse(
        "{\"sub\":\"12345\",\"preferred_username\":\"jane.doe\","
            + "\"email\":\"jane.doe@example.com\",\"name\":\"Jane Doe\"}");

    OAuthToken inputToken =
        new OAuthToken("dummyAccessToken", "dummySecretForTest", "dummyRawResponse");

    OAuthUserInfo userInfo = service.getUserInfo(inputToken);

    assertThat(userInfo).isNotNull();
    assertThat(userInfo.getExternalId()).isEqualTo(DISCOVERY_PROVIDER_PREFIX_FOR_TEST + "12345");
    assertThat(userInfo.getUserName()).isEqualTo("jane.doe");
    assertThat(userInfo.getEmailAddress()).isEqualTo("jane.doe@example.com");
    assertThat(userInfo.getDisplayName()).isEqualTo("Jane Doe");
    assertThat(userInfo.getClaimedIdentity()).isNull();
  }

  @Test
  public void getUserInfo_fallbackFields_shouldMapUserInfo() throws Exception {
    DiscoveryOAuthService service = createServiceWithDiscoveryDoc(validDiscoveryDocument());

    mockUserInfoResponse(
        "{\"sub\":\"67890\",\"username\":\"john\","
            + "\"email\":\"john@example.com\",\"display_name\":\"John Doe\"}");

    OAuthToken inputToken =
        new OAuthToken("dummyAccessToken", "dummySecretForTest", "dummyRawResponse");

    OAuthUserInfo userInfo = service.getUserInfo(inputToken);

    assertThat(userInfo).isNotNull();
    assertThat(userInfo.getExternalId()).isEqualTo(DISCOVERY_PROVIDER_PREFIX_FOR_TEST + "67890");
    assertThat(userInfo.getUserName()).isEqualTo("john");
    assertThat(userInfo.getEmailAddress()).isEqualTo("john@example.com");
    assertThat(userInfo.getDisplayName()).isEqualTo("John Doe");
  }

  @Test
  public void getUserInfo_missingSub_shouldThrowIOException() throws Exception {
    DiscoveryOAuthService service = createServiceWithDiscoveryDoc(validDiscoveryDocument());

    mockUserInfoResponse(
        "{\"preferred_username\":\"jane.doe\",\"email\":\"jane.doe@example.com\"}");

    OAuthToken inputToken =
        new OAuthToken("dummyAccessToken", "dummySecretForTest", "dummyRawResponse");

    try {
      service.getUserInfo(inputToken);
    } catch (IOException e) {
      assertThat(e).hasMessageThat().contains("sub");
      return;
    }

    throw new AssertionError("expected IOException");
  }

  @Test
  public void getUserInfo_nonObjectJson_shouldThrowIOException() throws Exception {
    DiscoveryOAuthService service = createServiceWithDiscoveryDoc(validDiscoveryDocument());

    mockUserInfoResponse("[]");

    OAuthToken inputToken =
        new OAuthToken("dummyAccessToken", "dummySecretForTest", "dummyRawResponse");

    try {
      service.getUserInfo(inputToken);
    } catch (IOException e) {
      assertThat(e).hasMessageThat().contains("not a JSON Object");
      return;
    }

    throw new AssertionError("expected IOException");
  }

  @Test
  public void constructor_missingRootUrl_shouldThrowProvisionException() {
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL)).thenReturn(null);

    ProvisionException e = assertConstructorProvisionException();

    assertThat(e).hasMessageThat().contains("Root URL must be configured");
  }

  @Test
  public void constructor_relativeRootUrl_shouldThrowProvisionException() {
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL)).thenReturn("/relative/path");

    ProvisionException e = assertConstructorProvisionException();

    assertThat(e).hasMessageThat().contains("Root URL must be absolute URL");
  }

  @Test
  public void constructor_unsupportedRootUrlScheme_shouldThrowProvisionException() {
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL))
        .thenReturn("ftp://id.example.com/realm");

    ProvisionException e = assertConstructorProvisionException();

    assertThat(e).hasMessageThat().contains("Root URL must use http or https");
  }

  @Test
  public void constructor_nullDiscoveryDocument_shouldThrowProvisionException() {
    ProvisionException e = assertProvisionException(null);

    assertThat(e).hasMessageThat().contains("Discovery document is empty");
  }

  @Test
  public void constructor_missingIssuer_shouldThrowProvisionException() {
    DiscoveryOpenIdConnect discovery =
        mockDiscoveryDocument(
            null, TEST_AUTHORIZATION_ENDPOINT, TEST_TOKEN_ENDPOINT, TEST_USERINFO_ENDPOINT);

    ProvisionException e = assertProvisionException(discovery);

    assertThat(e).hasMessageThat().contains("missing required field: issuer");
  }

  @Test
  public void constructor_malformedTokenEndpoint_shouldThrowProvisionException() {
    DiscoveryOpenIdConnect discovery =
        mockDiscoveryDocument(
            TEST_ISSUER, TEST_AUTHORIZATION_ENDPOINT, "http://[invalid", TEST_USERINFO_ENDPOINT);

    ProvisionException e = assertProvisionException(discovery);

    assertThat(e).hasMessageThat().contains("not a valid URL: token_endpoint");
  }

  @Test
  public void constructor_relativeUserinfoEndpoint_shouldThrowProvisionException() {
    DiscoveryOpenIdConnect discovery =
        mockDiscoveryDocument(
            TEST_ISSUER,
            TEST_AUTHORIZATION_ENDPOINT,
            TEST_TOKEN_ENDPOINT,
            "/protocol/openid-connect/userinfo");

    ProvisionException e = assertProvisionException(discovery);

    assertThat(e).hasMessageThat().contains("userinfo_endpoint");
    assertThat(e).hasMessageThat().contains("absolute URL");
  }

  @Test
  public void constructor_unsupportedUserinfoEndpointScheme_shouldThrowProvisionException() {
    DiscoveryOpenIdConnect discovery =
        mockDiscoveryDocument(
            TEST_ISSUER,
            TEST_AUTHORIZATION_ENDPOINT,
            TEST_TOKEN_ENDPOINT,
            "ftp://id.example.com/realms/gerrit/protocol/openid-connect/userinfo");

    ProvisionException e = assertProvisionException(discovery);

    assertThat(e).hasMessageThat().contains("must use http or https: userinfo_endpoint");
  }
}
