// Copyright (C) 2018 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth;

import com.github.scribejava.core.builder.api.DefaultApi20;
import com.github.scribejava.core.oauth2.clientauthentication.ClientAuthentication;
import com.github.scribejava.core.oauth2.clientauthentication.RequestBodyAuthenticationScheme;

public class Office365Api extends DefaultApi20 {
  public static final String DEFAULT_TENANT = "organizations";

  private final String tenant;

  public Office365Api() {
    this(DEFAULT_TENANT);
  }

  public Office365Api(String tenant) {
    this.tenant = tenant;
  }

  @Override
  public String getAccessTokenEndpoint() {
    return endpointFor("token");
  }

  @Override
  public String getAuthorizationBaseUrl() {
    return endpointFor("authorize");
  }

  private String endpointFor(String suffix) {
    return String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/%s", tenant, suffix);
  }

  @Override
  public ClientAuthentication getClientAuthentication() {
    return RequestBodyAuthenticationScheme.instance();
  }
}
