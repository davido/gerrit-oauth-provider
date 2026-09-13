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

package com.googlesource.gerrit.plugins.oauth.azure;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
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
public class AzureActiveDirectoryServiceTest {
  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private OAuthClient mockClient;
  @Mock private HttpOAuthClientFactory mockServiceFactory;

  private static final String CLIENT_ID = "gerrit-client";
  private static final String FIXED_TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";
  private static final String ISSUER =
      "https://login.microsoftonline.com/" + FIXED_TENANT + "/v2.0";
  private static final String EXT_ID_PREFIX = "azure-oauth:";
  private static final String GRAPH_BODY =
      "{\"id\":\"graph-id-123\",\"mail\":\"u@contoso.com\",\"displayName\":\"User\"}";

  private static RSAKey rsaKey;
  private static RSAKey otherKey;
  private static JWKSource<SecurityContext> jwks;

  @BeforeClass
  public static void generateKeys() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("primary").generate();
    otherKey = new RSAKeyGenerator(2048).keyID("attacker").generate();
    jwks = new ImmutableJWKSet<>(new JWKSet(List.of(rsaKey.toPublicJWK())));
  }

  @Before
  public void setUp() {
    when(mockConfigFactory.create(AzureActiveDirectoryService.PROVIDER_NAME))
        .thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_ID)).thenReturn(CLIENT_ID);
    when(mockPluginConfig.getString(
            OAuthConfigKeys.TENANT, AzureActiveDirectoryService.DEFAULT_TENANT))
        .thenReturn(FIXED_TENANT);
    when(mockServiceFactory.create(anyString(), any(OAuthProviderEndpoints.class)))
        .thenReturn(mockClient);
  }

  private AzureActiveDirectoryService fixedTenantService() {
    return new AzureActiveDirectoryService(mockConfigFactory, mockServiceFactory, testValidator());
  }

  @Test
  public void constructor_blankTenant_fallsBackToDefaultTenant() {
    // A blank tenant must normalize to the default, not produce a malformed
    // "https://login.microsoftonline.com//oauth2/..." endpoint.
    when(mockPluginConfig.getString(
            OAuthConfigKeys.TENANT, AzureActiveDirectoryService.DEFAULT_TENANT))
        .thenReturn("");

    new AzureActiveDirectoryService(mockConfigFactory, mockServiceFactory, testValidator());

    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockServiceFactory)
        .create(eq(AzureActiveDirectoryService.PROVIDER_NAME), captor.capture());
    assertThat(captor.getValue().authorizationEndpoint())
        .isEqualTo(
            "https://login.microsoftonline.com/"
                + AzureActiveDirectoryService.DEFAULT_TENANT
                + "/oauth2/v2.0/authorize");
  }

  @Test
  public void constructor_buildsAzureDescriptor() {
    when(mockPluginConfig.getBoolean(OAuthConfigKeys.ENABLE_PKCE, false)).thenReturn(true);

    fixedTenantService();

    ArgumentCaptor<OAuthProviderEndpoints> captor =
        ArgumentCaptor.forClass(OAuthProviderEndpoints.class);
    verify(mockServiceFactory)
        .create(eq(AzureActiveDirectoryService.PROVIDER_NAME), captor.capture());
    OAuthProviderEndpoints ep = captor.getValue();
    assertThat(ep.authorizationEndpoint())
        .isEqualTo("https://login.microsoftonline.com/" + FIXED_TENANT + "/oauth2/v2.0/authorize");
    assertThat(ep.tokenEndpoint())
        .isEqualTo("https://login.microsoftonline.com/" + FIXED_TENANT + "/oauth2/v2.0/token");
    assertThat(ep.scope())
        .isEqualTo("openid offline_access https://graph.microsoft.com/user.readbasic.all");
    assertThat(ep.clientAuthStyle()).isEqualTo(ClientAuthStyle.REQUEST_BODY);
    assertThat(ep.bearerPlacement()).isEqualTo(BearerPlacement.AUTHORIZATION_HEADER);
    assertThat(ep.tokenResponseFormat()).isEqualTo(TokenResponseFormat.JSON);
    assertThat(ep.tolerateMissingTokenType()).isFalse();
    assertThat(ep.enablePkce()).isTrue();
  }

  private void mockGraph() throws Exception {
    when(mockClient.get(any(URI.class), any(OAuthToken.class), any())).thenReturn(GRAPH_BODY);
  }

  @Test
  public void getUserInfo_validIdToken_fixedTenant_mapsFromGraphId() throws Exception {
    mockGraph();

    OAuthUserInfo info = fixedTenantService().getUserInfo(idTokenResponse(sign(claims().build())));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_PREFIX + "graph-id-123");
    assertThat(info.getEmailAddress()).isEqualTo("u@contoso.com");
  }

  @Test
  public void getUserInfo_badSignature_rejectedBeforeGraph() throws Exception {
    // Signed by a key absent from the JWKS -> signature verification fails.
    String jwt = signWith(otherKey, claims().build());

    assertThrows(IOException.class, () -> fixedTenantService().getUserInfo(idTokenResponse(jwt)));
    verify(mockClient, never()).get(any(URI.class), any(OAuthToken.class), any());
  }

  @Test
  public void getUserInfo_wrongAudience_rejectedBeforeGraph() throws Exception {
    String jwt = sign(claims().audience("some-other-client").build());

    assertThrows(IOException.class, () -> fixedTenantService().getUserInfo(idTokenResponse(jwt)));
    verify(mockClient, never()).get(any(URI.class), any(OAuthToken.class), any());
  }

  @Test
  public void getUserInfo_wrongTenantId_returnsNullBeforeGraph() throws Exception {
    // Signature/aud/iss valid, but the verified tid is a different tenant.
    String jwt = sign(claims().claim("tid", OTHER_TENANT).build());

    assertThat(fixedTenantService().getUserInfo(idTokenResponse(jwt))).isNull();
    verify(mockClient, never()).get(any(URI.class), any(OAuthToken.class), any());
  }

  @Test
  public void getUserInfo_multiTenantAlias_validatorNull_preservesLegacyPath() throws Exception {
    when(mockPluginConfig.getString(
            OAuthConfigKeys.TENANT, AzureActiveDirectoryService.DEFAULT_TENANT))
        .thenReturn(AzureActiveDirectoryService.DEFAULT_TENANT);
    mockGraph();
    AzureActiveDirectoryService service =
        new AzureActiveDirectoryService(
            mockConfigFactory, mockServiceFactory, /* providedValidator= */ null);

    // Multi-tenant alias -> no JWKS validation; the legacy unverified aud parse still maps the
    // user.
    OAuthUserInfo info = service.getUserInfo(idTokenResponse(sign(claims().build())));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_PREFIX + "graph-id-123");
  }

  @Test
  public void getUserInfo_externalIdFromGraphId_notOid_compatibilityNonGoal() throws Exception {
    // Non-goal guard: even when the id_token's oid/sub differ from Graph /me.id, the external id
    // must come from Graph /me.id. Switching to oid would rewrite external ids and can lock users
    // out; do not "fix" this without a live tenant probe.
    mockGraph();
    String jwt =
        sign(claims().subject("oid-different-999").claim("oid", "oid-different-999").build());

    OAuthUserInfo info = fixedTenantService().getUserInfo(idTokenResponse(jwt));

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_PREFIX + "graph-id-123");
  }

  @Test
  public void constructor_fixedTenantMissingClientId_throwsProvisionException() {
    // Fail closed: a fixed tenant without client-id must not fall back to unverified parsing.
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_ID)).thenReturn(null);

    assertThrows(
        ProvisionException.class,
        () ->
            new AzureActiveDirectoryService(
                mockConfigFactory, mockServiceFactory, /* providedValidator= */ null));
  }

  @Test
  public void constructor_fixedTenantBlankClientId_throwsProvisionException() {
    // A blank client-id must fail the same as missing, not build an empty-audience validator.
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_ID)).thenReturn("");

    assertThrows(
        ProvisionException.class,
        () ->
            new AzureActiveDirectoryService(
                mockConfigFactory, mockServiceFactory, /* providedValidator= */ null));
  }

  private static OidcJwtValidator testValidator() {
    return OidcJwtValidator.builder().issuer(ISSUER).audience(CLIENT_ID).jwkSource(jwks).build();
  }

  private static JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .subject("user-oid-abc")
        .issuer(ISSUER)
        .audience(CLIENT_ID)
        .claim("tid", FIXED_TENANT)
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plusSeconds(300)));
  }

  private static String sign(JWTClaimsSet claimSet) throws Exception {
    return signWith(rsaKey, claimSet);
  }

  private static String signWith(RSAKey key, JWTClaimsSet claimSet) throws Exception {
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(key.getKeyID())
                .build(),
            claimSet);
    signed.sign(new RSASSASigner(key));
    return signed.serialize();
  }

  private static OAuthToken idTokenResponse(String jwt) {
    JsonObject raw = new JsonObject();
    raw.addProperty("id_token", jwt);
    return new OAuthToken("access", "Bearer", raw.toString());
  }
}
