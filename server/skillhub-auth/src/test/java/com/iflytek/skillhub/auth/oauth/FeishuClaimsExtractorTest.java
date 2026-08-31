package com.iflytek.skillhub.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class FeishuClaimsExtractorTest {

    private final FeishuClaimsExtractor extractor = new FeishuClaimsExtractor();

    @Test
    void extractUsesStableUnionIdAndEnterpriseEmail() {
        Map<String, Object> profile = Map.of(
                "union_id", "on_union_123",
                "open_id", "ou_open_123",
                "tenant_key", "tenant-zhihu",
                "name", "Alice",
                "enterprise_email", "alice@sota-lab.cn",
                "avatar_url", "https://example.com/avatar.png"
        );
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(),
                Map.of("code", 0, "data", profile),
                "data"
        );

        OAuthClaims claims = extractor.extract(userRequest(), user);

        assertThat(claims.provider()).isEqualTo("feishu");
        assertThat(claims.subject()).isEqualTo("on_union_123");
        assertThat(claims.providerLogin()).isEqualTo("Alice");
        assertThat(claims.email()).isEqualTo("alice@sota-lab.cn");
        assertThat(claims.emailVerified()).isTrue();
        assertThat(claims.extra()).containsEntry("open_id", "ou_open_123");
        assertThat(claims.extra()).containsEntry("tenant_key", "tenant-zhihu");
    }

    @Test
    void extractRejectsPayloadWithoutFeishuIdentity() {
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(),
                Map.of("data", Map.of("name", "Alice")),
                "data"
        );

        assertThatThrownBy(() -> extractor.extract(userRequest(), user))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("missing");
    }

    private static OAuth2UserRequest userRequest() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("feishu")
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("contact:user.base:readonly", "contact:user.email:readonly")
                .authorizationUri("https://accounts.feishu.cn/open-apis/authen/v1/authorize")
                .tokenUri("https://open.feishu.cn/open-apis/authen/v2/oauth/token")
                .userInfoUri("https://open.feishu.cn/open-apis/authen/v1/user_info")
                .userNameAttributeName("data")
                .clientName("Feishu")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "token-123",
                Instant.now(),
                Instant.now().plusSeconds(3600)
        );
        return new OAuth2UserRequest(registration, accessToken);
    }
}
