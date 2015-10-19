package com.googlesource.gerrit.plugins.oauth;

import com.google.common.io.BaseEncoding;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.scribe.builder.api.DefaultApi20;
import org.scribe.exceptions.OAuthException;
import org.scribe.extractors.AccessTokenExtractor;
import org.scribe.model.*;
import org.scribe.oauth.OAuthService;

import static com.google.gerrit.server.OutputFormat.JSON;
import static java.lang.String.format;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.scribe.model.OAuthConstants.ACCESS_TOKEN;
import static org.scribe.model.OAuthConstants.CODE;

public class BitbucketApi extends DefaultApi20 {

  private static final String AUTHORIZE_URL = "https://bitbucket.org/site/oauth2/authorize?client_id=%s&response_type=code";
  private static final String ACCESS_TOKEN_ENDPOINT = "https://bitbucket.org/site/oauth2/access_token";

  @Override
  public String getAuthorizationUrl(OAuthConfig config) {
    return format(AUTHORIZE_URL, config.getApiKey());
  }

  @Override
  public String getAccessTokenEndpoint() {
    return ACCESS_TOKEN_ENDPOINT;
  }

  @Override
  public Verb getAccessTokenVerb() {
    return Verb.POST;
  }

  @Override
  public OAuthService createService(OAuthConfig config) {
    return new BitbucketOAuthService(this, config);
  }

  @Override
  public AccessTokenExtractor getAccessTokenExtractor() {
    return new BitbucketTokenExtractor();
  }

  private static final class BitbucketOAuthService implements OAuthService {
    private static final String VERSION = "2.0";

    private static final String GRANT_TYPE = "grant_type";
    private static final String GRANT_TYPE_VALUE = "authorization_code";

    private final DefaultApi20 api;
    private final OAuthConfig config;

    private BitbucketOAuthService(DefaultApi20 api, OAuthConfig config) {
      this.config = config;
      this.api = api;
    }

    @Override
    public Token getAccessToken(Token token, Verifier verifier) {
      OAuthRequest request = new OAuthRequest(api.getAccessTokenVerb(), api.getAccessTokenEndpoint());
      request.addHeader("Authorization", "Basic " + BaseEncoding.base64().encode(String.format("%s:%s", config.getApiKey(), config.getApiSecret()).getBytes()));
      request.addBodyParameter(GRANT_TYPE, GRANT_TYPE_VALUE);
      request.addBodyParameter(CODE, verifier.getValue());
      Response response = request.send();
      if (response.getCode() == SC_OK) {
        Token t = api.getAccessTokenExtractor().extract(response.getBody());
        return new Token(t.getToken(), config.getApiSecret());
      } else {
        throw new OAuthException(String.format("Error response received: %s, HTTP status: %s", response.getBody(), response.getCode()));
      }
    }

    @Override
    public Token getRequestToken() {
      throw new UnsupportedOperationException(
              "Unsupported operation, please use 'getAuthorizationUrl' and redirect your users there");
    }

    @Override
    public String getVersion() {
      return VERSION;
    }

    @Override
    public void signRequest(Token token, OAuthRequest request) {
      request.addQuerystringParameter(ACCESS_TOKEN, token.getToken());
    }

    @Override
    public String getAuthorizationUrl(Token token) {
      return api.getAuthorizationUrl(config);
    }
  }

  private static final class BitbucketTokenExtractor implements AccessTokenExtractor {

    @Override
    public Token extract(String response) {
      JsonElement json = JSON.newGson().fromJson(response, JsonElement.class);
      if (json.isJsonObject()) {
        JsonObject jsonObject = json.getAsJsonObject();
        JsonElement id = jsonObject.get(ACCESS_TOKEN);
        if (id == null || id.isJsonNull()) {
          throw new OAuthException("Response doesn't contain 'access_token' field");
        }
        JsonElement accessToken = jsonObject.get(ACCESS_TOKEN);
        return new Token(accessToken.getAsString(), "");
      } else {
        throw new OAuthException(String.format("Invalid JSON '%s': not a JSON Object", json));
      }
    }
  }
}