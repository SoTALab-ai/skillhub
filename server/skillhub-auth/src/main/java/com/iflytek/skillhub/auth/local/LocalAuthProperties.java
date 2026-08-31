package com.iflytek.skillhub.auth.local;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Controls whether the built-in username/password authentication endpoints are available.
 */
@Component
@ConfigurationProperties(prefix = "skillhub.auth.local")
public class LocalAuthProperties {

    /**
     * Disabled by default for SSO-only deployments.
     */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
