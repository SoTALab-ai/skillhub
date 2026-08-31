package com.iflytek.skillhub.auth.oauth;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Normalizes the nested user-info payload returned by Feishu OAuth.
 */
@Component
public class FeishuClaimsExtractor implements OAuthClaimsExtractor {

    @Override
    public String getProvider() {
        return "feishu";
    }

    @Override
    public OAuthClaims extract(OAuth2UserRequest request, OAuth2User oAuth2User) {
        Map<String, Object> profile = profile(oAuth2User.getAttributes());
        String subject = firstPresent(
                asString(profile.get("union_id")),
                asString(profile.get("open_id")),
                asString(profile.get("user_id"))
        );
        if (subject == null) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("missing_subject", "Feishu user identity is missing", null));
        }

        String email = firstPresent(
                asString(profile.get("enterprise_email")),
                asString(profile.get("email"))
        );
        String providerLogin = firstPresent(
                asString(profile.get("name")),
                asString(profile.get("en_name")),
                email,
                subject
        );

        return new OAuthClaims(
                getProvider(),
                subject,
                email,
                email != null,
                providerLogin,
                profile
        );
    }

    private static Map<String, Object> profile(Map<String, Object> attributes) {
        Object data = attributes.get("data");
        if (!(data instanceof Map<?, ?> nested)) {
            return attributes;
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        nested.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                profile.put(stringKey, value);
            }
        });
        return profile;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : null;
    }
}
