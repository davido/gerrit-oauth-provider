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

package com.googlesource.gerrit.plugins.oauth.base;

/**
 * Config key names shared by the init wizard and the provider services. Kept in the base layer so
 * providers can read them without depending on {@code InitOAuth} (the init step lives in the plugin
 * target and references every provider).
 */
public final class OAuthConfigKeys {
  public static final String CLIENT_ID = "client-id";
  public static final String CLIENT_SECRET = "client-secret";
  public static final String TRUSTED_AUDIENCE = "trusted-audience";
  public static final String ENABLE_GIT_OVER_HTTP = "enable-git-over-http";
  public static final String REQUIRED_SCOPE = "required-scope";
  public static final String ENABLE_PKCE = "enable-pkce";
  public static final String ENABLE_TOKEN_REFRESH = "enable-token-refresh";
  public static final String FORCE_CONSENT = "force-consent";
  public static final String EXTERNAL_ID_SCHEME = "external-id-scheme";
  public static final String CLIENT_AUTH_METHOD = "client-auth-method";
  public static final String LINK_TO_EXISTING_OPENID_ACCOUNT = "link-to-existing-openid-accounts";
  public static final String FIX_LEGACY_USER_ID = "fix-legacy-user-id";
  public static final String DOMAIN = "domain";
  public static final String USE_EMAIL_AS_USERNAME = "use-email-as-username";
  public static final String USE_PREFERRED_USERNAME = "use-preferred-username";
  public static final String ROOT_URL = "root-url";
  public static final String REALM = "realm";
  public static final String TENANT = "tenant";
  public static final String LINK_TO_EXISTING_OFFICE365_ACCOUNT =
      "link-to-existing-office365-accounts";
  public static final String LINK_TO_EXISTING_GERRIT_ACCOUNT = "link-to-existing-gerrit-accounts";
  public static final String SERVICE_NAME = "service-name";

  private OAuthConfigKeys() {}
}
