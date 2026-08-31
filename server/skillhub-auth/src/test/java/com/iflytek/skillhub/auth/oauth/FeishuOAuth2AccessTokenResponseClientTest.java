package com.iflytek.skillhub.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FeishuOAuth2AccessTokenResponseClientTest {

    @Test
    void exchangesFeishuAuthorizationCodeUsingJsonBody() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v2/oauth/token"))
                .andExpect(content().json("""
                        {
                          "grant_type":"authorization_code",
                          "client_id":"client-id",
                          "client_secret":"client-secret",
                          "code":"authorization-code",
                          "redirect_uri":"https://skills.sota-lab.cn/login/oauth2/code/feishu"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "code":0,
                          "access_token":"u-access-token",
                          "expires_in":7200,
                          "refresh_token":"ur-refresh-token",
                          "refresh_token_expires_in":2592000,
                          "scope":"contact:user.base:readonly contact:user.email:readonly",
                          "token_type":"Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        @SuppressWarnings("unchecked")
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> delegate = mock(
                OAuth2AccessTokenResponseClient.class);
        FeishuOAuth2AccessTokenResponseClient client =
                new FeishuOAuth2AccessTokenResponseClient(delegate, restClientBuilder);

        OAuth2AccessTokenResponse response = client.getTokenResponse(grantRequest("feishu"));

        assertThat(response.getAccessToken().getTokenValue()).isEqualTo("u-access-token");
        assertThat(response.getRefreshToken().getTokenValue()).isEqualTo("ur-refresh-token");
        assertThat(response.getAccessToken().getScopes()).containsExactlyInAnyOrder(
                "contact:user.base:readonly", "contact:user.email:readonly");
        server.verify();
    }

    @Test
    void delegatesNonFeishuProvidersToSpringClient() {
        @SuppressWarnings("unchecked")
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> delegate = mock(
                OAuth2AccessTokenResponseClient.class);
        OAuth2AuthorizationCodeGrantRequest request = grantRequest("github");
        OAuth2AccessTokenResponse expected = OAuth2AccessTokenResponse.withToken("github-token")
                .tokenType(org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER)
                .build();
        given(delegate.getTokenResponse(request)).willReturn(expected);

        FeishuOAuth2AccessTokenResponseClient client =
                new FeishuOAuth2AccessTokenResponseClient(delegate, RestClient.builder());

        assertThat(client.getTokenResponse(request)).isSameAs(expected);
    }

    private static OAuth2AuthorizationCodeGrantRequest grantRequest(String registrationId) {
        String redirectUri = "https://skills.sota-lab.cn/login/oauth2/code/" + registrationId;
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(Set.of("contact:user.base:readonly", "contact:user.email:readonly"))
                .authorizationUri("https://accounts.feishu.cn/open-apis/authen/v1/authorize")
                .tokenUri("https://open.feishu.cn/open-apis/authen/v2/oauth/token")
                .userInfoUri("https://open.feishu.cn/open-apis/authen/v1/user_info")
                .userNameAttributeName("data")
                .clientName("Provider")
                .build();
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(redirectUri)
                .scopes(registration.getScopes())
                .state("state-123")
                .build();
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse
                .success("authorization-code")
                .redirectUri(redirectUri)
                .state("state-123")
                .build();
        return new OAuth2AuthorizationCodeGrantRequest(
                registration,
                new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse)
        );
    }
}
