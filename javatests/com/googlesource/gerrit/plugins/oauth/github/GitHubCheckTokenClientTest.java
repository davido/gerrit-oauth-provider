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

package com.googlesource.gerrit.plugins.oauth.github;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonObject;
import com.google.inject.ProvisionException;
import com.googlesource.gerrit.plugins.oauth.base.OAuthConfigKeys;
import com.googlesource.gerrit.plugins.oauth.base.OAuthPluginConfigFactory;
import com.googlesource.gerrit.plugins.oauth.github.GitHubCheckTokenClient.Validated;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GitHubCheckTokenClientTest {
  private static final String CLIENT_ID = "client123";

  @Mock private OAuthPluginConfigFactory mockConfigFactory;
  @Mock private PluginConfig mockPluginConfig;

  @Before
  public void setUp() {
    when(mockConfigFactory.create(GitHubOAuthService.PROVIDER_NAME)).thenReturn(mockPluginConfig);
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_ID)).thenReturn(CLIENT_ID);
    lenient()
        .when(
            mockPluginConfig.getString(
                OAuthConfigKeys.ROOT_URL, GitHubOAuthService.GITHUB_ROOT_URL))
        .thenReturn(GitHubOAuthService.GITHUB_ROOT_URL);
    lenient().when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_SECRET)).thenReturn("secret");
  }

  @Test
  public void validate_validToken_mapsUserAndExpiry() throws Exception {
    Validated validated = client(validResponse()).validate("gho_token");

    assertThat(validated.userInfo.getExternalId()).isEqualTo("github-oauth:12345");
    assertThat(validated.userInfo.getUserName()).isEqualTo("octocat");
    assertThat(validated.userInfo.getEmailAddress()).isEqualTo("octo@github.com");
    assertThat(validated.expiresAtMillis).isPresent();
  }

  @Test
  public void validate_tokenForDifferentApp_throwsIOException() throws Exception {
    JsonObject response = validResponse();
    response.getAsJsonObject("app").addProperty("client_id", "some-other-app");

    assertThrows(IOException.class, () -> client(response).validate("gho_token"));
  }

  @Test
  public void validate_missingUser_throwsIOException() throws Exception {
    JsonObject response = validResponse();
    response.remove("user");

    assertThrows(IOException.class, () -> client(response).validate("gho_token"));
  }

  @Test
  public void constructor_missingClientSecret_throwsProvisionException() {
    when(mockPluginConfig.getString(OAuthConfigKeys.CLIENT_SECRET)).thenReturn(null);

    assertThrows(ProvisionException.class, () -> client(validResponse()));
  }

  private GitHubCheckTokenClient client(JsonObject cannedResponse) {
    return new GitHubCheckTokenClient(mockConfigFactory) {
      @Override
      JsonObject checkToken(String bearerToken) {
        return cannedResponse;
      }
    };
  }

  private static JsonObject validResponse() {
    JsonObject app = new JsonObject();
    app.addProperty("client_id", CLIENT_ID);
    JsonObject user = new JsonObject();
    user.addProperty("id", 12345);
    user.addProperty("login", "octocat");
    user.addProperty("email", "octo@github.com");
    user.addProperty("name", "The Octocat");
    JsonObject response = new JsonObject();
    response.add("app", app);
    response.add("user", user);
    response.addProperty("expires_at", "2999-01-01T00:00:00Z");
    return response;
  }
}
