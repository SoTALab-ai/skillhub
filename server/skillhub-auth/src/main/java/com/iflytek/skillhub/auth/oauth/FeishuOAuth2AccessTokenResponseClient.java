package com.iflytek.skillhub.auth.oauth;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Uses Feishu's JSON token exchange while preserving Spring's standard client for other providers.
 */
@Component
public class FeishuOAuth2AccessTokenResponseClient
        implements OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> {

    private static final String FEISHU_REGISTRATION_ID = "feishu";

    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> delegate;
    private final RestClient restClient;

    public FeishuOAuth2AccessTokenResponseClient() {
        this(new DefaultAuthorizationCodeTokenResponseClient(), RestClient.builder());
    }

    FeishuOAuth2AccessTokenResponseClient(
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> delegate,
            RestClient.Builder restClientBuilder) {
        this.delegate = delegate;
        this.restClient = restClientBuilder
                .defaultHeaders(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
                })
                .build();
    }

    @Override
    public OAuth2AccessTokenResponse getTokenResponse(OAuth2AuthorizationCodeGrantRequest request) {
        if (!FEISHU_REGISTRATION_ID.equals(request.getClientRegistration().getRegistrationId())) {
            return delegate.getTokenResponse(request);
        }

        var registration = request.getClientRegistration();
        var authorizationExchange = request.getAuthorizationExchange();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("grant_type", "authorization_code");
        payload.put("client_id", registration.getClientId());
        payload.put("client_secret", registration.getClientSecret());
        payload.put("code", authorizationExchange.getAuthorizationResponse().getCode());
        payload.put("redirect_uri", authorizationExchange.getAuthorizationRequest().getRedirectUri());
        String codeVerifier = authorizationExchange.getAuthorizationRequest()
                .getAttribute(PkceParameterNames.CODE_VERIFIER);
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            payload.put(PkceParameterNames.CODE_VERIFIER, codeVerifier);
        }

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri(registration.getProviderDetails().getTokenUri())
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException exception) {
            throw tokenResponseError("Feishu token endpoint request failed", exception);
        }

        if (response == null || number(response.get("code"), 0L) != 0L) {
            throw tokenResponseError("Feishu token endpoint rejected the authorization code", null);
        }

        String accessToken = string(response.get("access_token"));
        if (accessToken == null || accessToken.isBlank()) {
            throw tokenResponseError("Feishu token response did not contain an access token", null);
        }

        Set<String> scopes = parseScopes(response.get("scope"), registration.getScopes());
        long expiresIn = number(response.get("expires_in"), 0L);
        OAuth2AccessTokenResponse.Builder builder = OAuth2AccessTokenResponse.withToken(accessToken)
                .tokenType(TokenType.BEARER)
                .expiresIn(expiresIn)
                .scopes(scopes);

        String refreshToken = string(response.get("refresh_token"));
        if (refreshToken != null && !refreshToken.isBlank()) {
            builder.refreshToken(refreshToken);
        }

        Map<String, Object> additionalParameters = new LinkedHashMap<>(response);
        additionalParameters.keySet().removeAll(Set.of(
                "code", "msg", "access_token", "token_type", "expires_in", "refresh_token", "scope"));
        return builder.additionalParameters(additionalParameters).build();
    }

    private static Set<String> parseScopes(Object value, Set<String> fallback) {
        String scope = string(value);
        if (scope == null || scope.isBlank()) {
            return fallback;
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String item : scope.trim().split("\\s+")) {
            if (!item.isBlank()) {
                scopes.add(item);
            }
        }
        return scopes;
    }

    private static String string(Object value) {
        return value instanceof String string ? string : null;
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static OAuth2AuthorizationException tokenResponseError(String description, Throwable cause) {
        OAuth2Error error = new OAuth2Error("invalid_token_response", description, null);
        return cause == null
                ? new OAuth2AuthorizationException(error)
                : new OAuth2AuthorizationException(error, cause);
    }
}
