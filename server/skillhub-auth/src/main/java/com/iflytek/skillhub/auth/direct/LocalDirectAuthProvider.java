package com.iflytek.skillhub.auth.direct;

import com.iflytek.skillhub.auth.local.LocalAuthService;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Direct-auth provider that delegates username and password verification to the local auth flow.
 */
@Component
@ConditionalOnProperty(prefix = "skillhub.auth.local", name = "enabled", havingValue = "true")
public class LocalDirectAuthProvider implements DirectAuthProvider {

    private final LocalAuthService localAuthService;

    public LocalDirectAuthProvider(LocalAuthService localAuthService) {
        this.localAuthService = localAuthService;
    }

    @Override
    public String providerCode() {
        return "local";
    }

    @Override
    public String displayName() {
        return "Local Account";
    }

    @Override
    public PlatformPrincipal authenticate(DirectAuthRequest request) {
        return localAuthService.login(request.username(), request.password());
    }
}
