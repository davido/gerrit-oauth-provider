package com.googlesource.gerrit.plugins.oauth;

import org.scribe.builder.api.DefaultApi20;
import org.scribe.model.OAuthConfig;
import org.scribe.utils.OAuthEncoder;

public class GitHub2Api extends DefaultApi20 {
  private static final String AUTHORIZE_URL =
      "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s";

  @Override
  public String getAccessTokenEndpoint() {
    return "https://github.com/login/oauth/access_token";
  }

  @Override
  public String getAuthorizationUrl(OAuthConfig config) {
    return String.format(AUTHORIZE_URL, config.getApiKey(),
        OAuthEncoder.encode(config.getCallback()));
  }
}
