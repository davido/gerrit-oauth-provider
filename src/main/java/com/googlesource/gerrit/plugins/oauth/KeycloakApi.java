package com.googlesource.gerrit.plugins.oauth;

import org.scribe.builder.api.DefaultApi20;
import org.scribe.extractors.AccessTokenExtractor;
import org.scribe.extractors.JsonTokenExtractor;
import org.scribe.model.OAuthConfig;
import org.scribe.model.Verb;
import org.scribe.oauth.OAuthService;
import org.scribe.utils.OAuthEncoder;

public class KeycloakApi extends DefaultApi20 {

    private static final String AUTHORIZE_URL =
            "%s/auth/realms/%s/protocol/openid-connect/auth?client_id=%s&response_type=code&redirect_uri=%s&scope=%s";

    private final String rootUrl;
    private final String realm;

    public KeycloakApi(String rootUrl, String realm) {
        this.rootUrl = rootUrl;
        this.realm = realm;
    }

    @Override
    public String getAuthorizationUrl(OAuthConfig config) {
        return String.format(
                AUTHORIZE_URL,
                rootUrl,
                realm,
                config.getApiKey(),
                OAuthEncoder.encode(config.getCallback()),
                config.getScope().replaceAll(" ", "+"));
    }

    @Override
    public String getAccessTokenEndpoint() {
        return String.format("%s/auth/realms/%s/protocol/openid-connect/token", rootUrl, realm);
    }

    @Override
    public Verb getAccessTokenVerb() {
        return Verb.POST;
    }

    @Override
    public OAuthService createService(OAuthConfig config) {
        return new OAuth20ServiceImpl(this, config);
    }

    @Override
    public AccessTokenExtractor getAccessTokenExtractor() {
        return new JsonTokenExtractor();
    }
}
