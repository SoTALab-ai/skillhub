package com.iflytek.skillhub.auth.policy;

import com.iflytek.skillhub.auth.oauth.OAuthClaims;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Allows Feishu login only when the tenant selected for the current OAuth flow is allowlisted.
 *
 * <p>The tenant is read from the current {@code user_info} response on every login. It is not
 * inferred from the user's stable subject, so one person belonging to multiple enterprises cannot
 * reuse an allowed enterprise identity while authenticating through a different tenant.</p>
 */
public class FeishuTenantAllowlistAccessPolicy implements AccessPolicy {

    static final String FEISHU_PROVIDER = "feishu";
    static final String TENANT_KEY_CLAIM = "tenant_key";

    private final Set<String> allowedTenantKeys;

    public FeishuTenantAllowlistAccessPolicy(Set<String> allowedTenantKeys) {
        this.allowedTenantKeys = allowedTenantKeys.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public AccessDecision evaluate(OAuthClaims claims) {
        if (!FEISHU_PROVIDER.equals(claims.provider())) {
            return AccessDecision.DENY;
        }

        Object rawTenantKey = claims.extra().get(TENANT_KEY_CLAIM);
        if (!(rawTenantKey instanceof String tenantKey) || tenantKey.isBlank()) {
            return AccessDecision.DENY;
        }

        return allowedTenantKeys.contains(tenantKey.trim())
                ? AccessDecision.ALLOW
                : AccessDecision.DENY;
    }
}
