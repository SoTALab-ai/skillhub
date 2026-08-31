package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.iflytek.skillhub.auth.bootstrap.PassiveSessionAuthenticator;
import com.iflytek.skillhub.auth.direct.DirectAuthProvider;
import com.iflytek.skillhub.auth.direct.DirectAuthRequest;
import com.iflytek.skillhub.auth.local.LocalAuthProperties;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.config.AuthSessionBootstrapProperties;
import com.iflytek.skillhub.config.DirectAuthProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;

class AuthMethodCatalogTest {

    @Test
    void listMethodsShouldHideLocalPasswordWhenLocalAuthIsDisabled() {
        OAuth2ClientProperties oauthProperties = new OAuth2ClientProperties();
        oauthProperties.getRegistration().put("feishu", registration("client-id", "Feishu"));

        AuthMethodCatalog catalog = new AuthMethodCatalog(
            oauthProperties,
            new LocalAuthProperties(),
            new DirectAuthProperties(),
            new AuthSessionBootstrapProperties(),
            List.of(),
            List.of()
        );

        assertThat(catalog.listMethods(null))
            .extracting(method -> method.id())
            .containsExactly("oauth-feishu");
    }

    @Test
    void catalogsShouldHideEmptyAndPlaceholderOAuthProviders() {
        OAuth2ClientProperties oauthProperties = new OAuth2ClientProperties();
        oauthProperties.getRegistration().put("valid", registration("production-client", "Valid"));
        oauthProperties.getRegistration().put("missing", registration(null, "Missing"));
        oauthProperties.getRegistration().put("blank", registration("  ", "Blank"));
        oauthProperties.getRegistration().put("placeholder", registration("PLACEHOLDER", "Placeholder"));
        oauthProperties.getRegistration().put("local", registration("local-placeholder", "Local"));

        AuthMethodCatalog catalog = new AuthMethodCatalog(
            oauthProperties,
            enabledLocalAuth(),
            new DirectAuthProperties(),
            new AuthSessionBootstrapProperties(),
            List.of(),
            List.of()
        );

        assertThat(catalog.listOAuthProviders(null))
            .extracting(provider -> provider.id())
            .containsExactly("valid");
        assertThat(catalog.listMethods(null))
            .extracting(method -> method.id())
            .containsExactly("local-password", "oauth-valid");
    }

    @Test
    void listMethodsShouldUseProviderDisplayNamesForCompatibleAuthMethods() {
        OAuth2ClientProperties oauthProperties = new OAuth2ClientProperties();
        DirectAuthProperties directAuthProperties = new DirectAuthProperties();
        directAuthProperties.setEnabled(true);
        AuthSessionBootstrapProperties bootstrapProperties = new AuthSessionBootstrapProperties();
        bootstrapProperties.setEnabled(true);

        DirectAuthProvider directProvider = new DirectAuthProvider() {
            @Override
            public String providerCode() {
                return "private-sso";
            }

            @Override
            public String displayName() {
                return "Enterprise Password";
            }

            @Override
            public PlatformPrincipal authenticate(DirectAuthRequest request) {
                throw new UnsupportedOperationException("not used in catalog test");
            }
        };

        PassiveSessionAuthenticator bootstrapProvider = new PassiveSessionAuthenticator() {
            @Override
            public String providerCode() {
                return "private-sso";
            }

            @Override
            public String displayName() {
                return "Enterprise SSO";
            }

            @Override
            public Optional<PlatformPrincipal> authenticate(jakarta.servlet.http.HttpServletRequest request) {
                return Optional.empty();
            }
        };

        AuthMethodCatalog catalog = new AuthMethodCatalog(
            oauthProperties,
            enabledLocalAuth(),
            directAuthProperties,
            bootstrapProperties,
            List.of(directProvider),
            List.of(bootstrapProvider)
        );

        assertThat(catalog.listMethods(null))
            .extracting(method -> method.id() + ":" + method.displayName())
            .contains(
                "local-password:Local Account",
                "direct-private-sso:Enterprise Password",
                "bootstrap-private-sso:Enterprise SSO"
            );
    }

    @Test
    void listMethodsShouldFallBackToProviderCodeWhenDisplayNameIsNotOverridden() {
        OAuth2ClientProperties oauthProperties = new OAuth2ClientProperties();
        DirectAuthProperties directAuthProperties = new DirectAuthProperties();
        directAuthProperties.setEnabled(true);
        AuthSessionBootstrapProperties bootstrapProperties = new AuthSessionBootstrapProperties();
        bootstrapProperties.setEnabled(true);

        DirectAuthProvider directProvider = new DirectAuthProvider() {
            @Override
            public String providerCode() {
                return "private-sso";
            }

            @Override
            public PlatformPrincipal authenticate(DirectAuthRequest request) {
                return mock(PlatformPrincipal.class);
            }
        };

        PassiveSessionAuthenticator bootstrapProvider = new PassiveSessionAuthenticator() {
            @Override
            public String providerCode() {
                return "private-sso";
            }

            @Override
            public Optional<PlatformPrincipal> authenticate(jakarta.servlet.http.HttpServletRequest request) {
                return Optional.empty();
            }
        };

        AuthMethodCatalog catalog = new AuthMethodCatalog(
            oauthProperties,
            enabledLocalAuth(),
            directAuthProperties,
            bootstrapProperties,
            List.of(directProvider),
            List.of(bootstrapProvider)
        );

        assertThat(catalog.listMethods(null))
            .extracting(method -> method.id() + ":" + method.displayName())
            .contains(
                "direct-private-sso:private-sso",
                "bootstrap-private-sso:private-sso"
            );
    }

    private static OAuth2ClientProperties.Registration registration(String clientId, String clientName) {
        OAuth2ClientProperties.Registration registration = new OAuth2ClientProperties.Registration();
        registration.setClientId(clientId);
        registration.setClientName(clientName);
        return registration;
    }

    private static LocalAuthProperties enabledLocalAuth() {
        LocalAuthProperties properties = new LocalAuthProperties();
        properties.setEnabled(true);
        return properties;
    }
}
