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

package com.googlesource.gerrit.plugins.oauth.gitlab;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.base.OAuthServiceProviderExternalIdScheme;
import com.googlesource.gerrit.plugins.oauth.base.OAuthTokenValidationCache;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GitLabTokenInfoValidatorTest {
  private static final String APP_UID = "gerrit-app-uid";
  private static final String ROOT_URL = "https://gitlab.example.com";
  private static final String EXT_ID_SCHEME =
      OAuthServiceProviderExternalIdScheme.create(GitLabOAuthService.PROVIDER_NAME);

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;
  @Mock private OAuthTokenValidationCache mockCache;

  @Before
  public void setUp() {
    when(mockConfigFactory.create(GitLabOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.ROOT_URL)).thenReturn(ROOT_URL);
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_ID)).thenReturn(APP_UID);
    lenient()
        .when(mockPluginConfig.getStringList(OAuthConfigKeys.REQUIRED_SCOPE))
        .thenReturn(new String[0]);
    lenient()
        .when(mockPluginConfig.getStringList(OAuthConfigKeys.TRUSTED_AUDIENCE))
        .thenReturn(new String[0]);
    lenient().when(mockCache.get(anyString())).thenReturn(Optional.empty());
  }

  @Test
  public void validate_valid_mapsAndCaches() throws Exception {
    OAuthUserInfo info =
        validator(tokenInfo(APP_UID, List.of("read_user"), 42L, 3600L), user(42L))
            .validate("opaque-token");

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_SCHEME + ":42");
    assertThat(info.getUserName()).isEqualTo("tanuki");
    assertThat(info.getEmailAddress()).isEqualTo("t@gitlab.com");
    verify(mockCache).put(eq("opaque-token"), eq(info), anyLong());
  }

  @Test
  public void validate_wrongApplicationUid_throws() {
    assertThrows(
        IOException.class,
        () ->
            validator(tokenInfo("some-other-app", List.of("read_user"), 42L, 3600L), user(42L))
                .validate("opaque-token"));
  }

  @Test
  public void validate_trustedAudienceSecondApp_accepts() throws Exception {
    // A token minted by a second GitLab OAuth app (e.g. a git credential helper's client) whose
    // uid is listed in trusted-audience is accepted, beyond the browser client-id.
    String helperUid = "helper-app-uid";
    when(mockPluginConfig.getStringList(OAuthConfigKeys.TRUSTED_AUDIENCE))
        .thenReturn(new String[] {helperUid});

    OAuthUserInfo info =
        validator(tokenInfo(helperUid, List.of("read_user"), 42L, 3600L), user(42L))
            .validate("opaque-token");

    assertThat(info.getExternalId()).isEqualTo(EXT_ID_SCHEME + ":42");
  }

  @Test
  public void validate_trustedAudienceSet_stillRejectsUnknownApp() {
    when(mockPluginConfig.getStringList(OAuthConfigKeys.TRUSTED_AUDIENCE))
        .thenReturn(new String[] {"helper-app-uid"});

    // A third application uid, listed nowhere, is still rejected.
    assertThrows(
        IOException.class,
        () ->
            validator(tokenInfo("some-other-app", List.of("read_user"), 42L, 3600L), user(42L))
                .validate("opaque-token"));
  }

  @Test
  public void validate_missingRequiredScope_throws() {
    assertThrows(
        IOException.class,
        () ->
            validator(tokenInfo(APP_UID, List.of("read_repository"), 42L, 3600L), user(42L))
                .validate("opaque-token"));
  }

  @Test
  public void validate_subjectMismatch_throws() {
    assertThrows(
        IOException.class,
        () ->
            validator(tokenInfo(APP_UID, List.of("read_user"), 42L, 3600L), user(99L))
                .validate("opaque-token"));
  }

  @Test
  public void validate_missingResourceOwnerId_throws() {
    assertThrows(
        IOException.class,
        () ->
            validator(tokenInfo(APP_UID, List.of("read_user"), null, 3600L), user(42L))
                .validate("opaque-token"));
  }

  @Test
  public void validate_blockedAccount_throws() {
    assertThrows(
        IOException.class,
        () ->
            validator(tokenInfo(APP_UID, List.of("read_user"), 42L, 3600L), user(42L, "blocked"))
                .validate("opaque-token"));
  }

  @Test
  public void validate_missingAccountState_throws() {
    assertThrows(
        IOException.class,
        () ->
            validator(tokenInfo(APP_UID, List.of("read_user"), 42L, 3600L), user(42L, null))
                .validate("opaque-token"));
  }

  @Test
  public void validate_nonExpiringToken_notCached() throws Exception {
    validator(tokenInfo(APP_UID, List.of("read_user"), 42L, null), user(42L)).validate("t");

    verify(mockCache, never()).put(anyString(), any(OAuthUserInfo.class), anyLong());
  }

  @Test
  public void validate_customRequiredScope_enforced() {
    when(mockPluginConfig.getStringList(OAuthConfigKeys.REQUIRED_SCOPE))
        .thenReturn(new String[] {"api"});

    // read_user alone no longer satisfies the configured required scope "api".
    assertThrows(
        IOException.class,
        () ->
            validator(tokenInfo(APP_UID, List.of("read_user"), 42L, 3600L), user(42L))
                .validate("opaque-token"));
  }

  @Test
  public void validate_cacheHit_returnsCachedWithoutCalls() throws Exception {
    OAuthUserInfo cached = new OAuthUserInfo(EXT_ID_SCHEME + ":42", null, "t@e", null, null);
    when(mockCache.get("opaque-token")).thenReturn(Optional.of(cached));

    GitLabTokenInfoValidator validator =
        new GitLabTokenInfoValidator(mockConfigFactory, mockCache) {
          @Override
          JsonObject callTokenInfo(String bearerToken) {
            throw new AssertionError("token/info must not be called on a cache hit");
          }
        };

    assertThat(validator.validate("opaque-token")).isSameInstanceAs(cached);
  }

  private GitLabTokenInfoValidator validator(JsonObject tokenInfo, JsonObject user) {
    return new GitLabTokenInfoValidator(mockConfigFactory, mockCache) {
      @Override
      JsonObject callTokenInfo(String bearerToken) {
        return tokenInfo;
      }

      @Override
      JsonObject callUser(String bearerToken) {
        return user;
      }
    };
  }

  private static JsonObject tokenInfo(
      String appUid, List<String> scopes, Long resourceOwnerId, Long expiresIn) {
    JsonObject o = new JsonObject();
    JsonObject app = new JsonObject();
    app.addProperty("uid", appUid);
    o.add("application", app);
    JsonArray scope = new JsonArray();
    scopes.forEach(scope::add);
    o.add("scope", scope);
    if (resourceOwnerId != null) {
      o.addProperty("resource_owner_id", resourceOwnerId);
    }
    if (expiresIn != null) {
      o.addProperty("expires_in", expiresIn);
    }
    return o;
  }

  private static JsonObject user(long id) {
    return user(id, "active");
  }

  private static JsonObject user(long id, String state) {
    JsonObject o = new JsonObject();
    o.addProperty("id", id);
    o.addProperty("username", "tanuki");
    o.addProperty("email", "t@gitlab.com");
    o.addProperty("name", "The Tanuki");
    if (state != null) {
      o.addProperty("state", state);
    }
    return o;
  }
}
