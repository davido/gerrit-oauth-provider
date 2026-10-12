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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonObject;
import com.google.inject.ProvisionException;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCache;
import java.io.IOException;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GoogleTokenInfoValidatorTest {
  private static final String AUDIENCE = "gerrit-client.apps.googleusercontent.com";

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private OAuthTokenValidationCache mockCache;

  @Before
  public void setUp() {
    when(mockConfigFactory.create(GoogleOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_ID)).thenReturn("gerrit-client");
    lenient()
        .when(mockPluginConfig.getStringList(OAuthConfigKeys.DOMAIN))
        .thenReturn(new String[0]);
    lenient().when(mockCache.get(anyString())).thenReturn(Optional.empty());
  }

  @Test
  public void validate_validToken_mapsAndCaches() throws Exception {
    OAuthUserInfo userInfo = validator(validResponse()).validate("opaque-token");

    assertThat(userInfo.getExternalId()).isEqualTo("google-oauth:12345");
    assertThat(userInfo.getEmailAddress()).isEqualTo("jane@example.com");
    verify(mockCache).put(eq("opaque-token"), eq(userInfo), eq(9999999999L * 1000L));
  }

  @Test
  public void validate_legacyAccessTokenShape_mapsAndCaches() throws Exception {
    // Access-token tokeninfo field names: audience/issued_to/user_id/verified_email/expires_in.
    JsonObject legacy = new JsonObject();
    legacy.addProperty("audience", AUDIENCE);
    legacy.addProperty("issued_to", AUDIENCE);
    legacy.addProperty("email", "jane@example.com");
    legacy.addProperty("verified_email", "true");
    legacy.addProperty("user_id", "12345");
    legacy.addProperty("expires_in", "3599");

    OAuthUserInfo userInfo = validator(legacy).validate("opaque-token");

    assertThat(userInfo.getExternalId()).isEqualTo("google-oauth:12345");
    assertThat(userInfo.getEmailAddress()).isEqualTo("jane@example.com");
    verify(mockCache).put(eq("opaque-token"), eq(userInfo), anyLong());
  }

  @Test
  public void constructor_withDomainConfigured_throwsProvisionException() {
    // Git-over-HTTP cannot enforce the hosted-domain restriction (tokeninfo has no hd).
    when(mockPluginConfig.getStringList(OAuthConfigKeys.DOMAIN))
        .thenReturn(new String[] {"example.com"});

    assertThrows(ProvisionException.class, () -> validator(validResponse()));
  }

  @Test
  public void validate_cacheHit_returnsCachedWithoutIntrospection() throws Exception {
    OAuthUserInfo cached = new OAuthUserInfo("google-oauth:12345", null, "j@e", null, null);
    when(mockCache.get("opaque-token")).thenReturn(Optional.of(cached));

    // callTokeninfo throwing proves the introspection endpoint is not consulted on a cache hit.
    GoogleTokenInfoValidator validator =
        new GoogleTokenInfoValidator(mockConfigFactory, mockCache) {
          @Override
          JsonObject callTokeninfo(String bearerToken) throws IOException {
            throw new AssertionError("tokeninfo must not be called on a cache hit");
          }
        };

    assertThat(validator.validate("opaque-token")).isSameInstanceAs(cached);
  }

  @Test
  public void validate_wrongAudience_throwsIOException() throws Exception {
    JsonObject response = validResponse();
    response.addProperty("aud", "some-other-client.apps.googleusercontent.com");

    assertThrows(IOException.class, () -> validator(response).validate("opaque-token"));
  }

  @Test
  public void validate_trustedAudienceSecondClient_accepts() throws Exception {
    // A token minted by the Desktop client used by git credential helpers: its aud/azp differ from
    // the browser client-id but are listed in trusted-audience.
    String cliAudience = "gerrit-cli.apps.googleusercontent.com";
    when(mockPluginConfig.getStringList(OAuthConfigKeys.TRUSTED_AUDIENCE))
        .thenReturn(new String[] {"gerrit-cli"});
    JsonObject response = validResponse();
    response.addProperty("aud", cliAudience);
    response.addProperty("azp", cliAudience);

    OAuthUserInfo userInfo = validator(response).validate("opaque-token");

    assertThat(userInfo.getExternalId()).isEqualTo("google-oauth:12345");
  }

  @Test
  public void validate_trustedAudienceLegacyAccessTokenShape_accepts() throws Exception {
    // Access-token tokeninfo (audience/issued_to/user_id/verified_email) minted by the Desktop
    // client -- the shape the Git path actually consumes.
    String cliAudience = "gerrit-cli.apps.googleusercontent.com";
    when(mockPluginConfig.getStringList(OAuthConfigKeys.TRUSTED_AUDIENCE))
        .thenReturn(new String[] {"gerrit-cli"});
    JsonObject legacy = new JsonObject();
    legacy.addProperty("audience", cliAudience);
    legacy.addProperty("issued_to", cliAudience);
    legacy.addProperty("email", "jane@example.com");
    legacy.addProperty("verified_email", "true");
    legacy.addProperty("user_id", "12345");
    legacy.addProperty("expires_in", "3599");

    OAuthUserInfo userInfo = validator(legacy).validate("opaque-token");

    assertThat(userInfo.getExternalId()).isEqualTo("google-oauth:12345");
  }

  @Test
  public void validate_trustedAudienceSet_stillRejectsUnknownAudience() throws Exception {
    when(mockPluginConfig.getStringList(OAuthConfigKeys.TRUSTED_AUDIENCE))
        .thenReturn(new String[] {"gerrit-cli"});
    JsonObject response = validResponse();
    response.addProperty("aud", "attacker-client.apps.googleusercontent.com");

    assertThrows(IOException.class, () -> validator(response).validate("opaque-token"));
  }

  @Test
  public void validate_trustedAudienceAud_butForeignAzp_throwsIOException() throws Exception {
    // Confused-deputy: token names a trusted aud but was authorized to a different party.
    when(mockPluginConfig.getStringList(OAuthConfigKeys.TRUSTED_AUDIENCE))
        .thenReturn(new String[] {"gerrit-cli"});
    JsonObject response = validResponse();
    response.addProperty("aud", "gerrit-cli.apps.googleusercontent.com");
    response.addProperty("azp", "attacker-client.apps.googleusercontent.com");

    assertThrows(IOException.class, () -> validator(response).validate("opaque-token"));
  }

  @Test
  public void validate_emailNotVerified_throwsIOException() throws Exception {
    JsonObject response = validResponse();
    response.addProperty("email_verified", "false");

    assertThrows(IOException.class, () -> validator(response).validate("opaque-token"));
  }

  @Test
  public void validate_missingSub_throwsIOException() throws Exception {
    JsonObject response = validResponse();
    response.remove("sub");

    assertThrows(IOException.class, () -> validator(response).validate("opaque-token"));
  }

  private GoogleTokenInfoValidator validator(JsonObject cannedResponse) {
    return new GoogleTokenInfoValidator(mockConfigFactory, mockCache) {
      @Override
      JsonObject callTokeninfo(String bearerToken) {
        return cannedResponse;
      }
    };
  }

  private static JsonObject validResponse() {
    JsonObject o = new JsonObject();
    o.addProperty("aud", AUDIENCE);
    o.addProperty("azp", AUDIENCE);
    o.addProperty("email", "jane@example.com");
    o.addProperty("email_verified", "true");
    o.addProperty("sub", "12345");
    o.addProperty("exp", "9999999999");
    return o;
  }
}
