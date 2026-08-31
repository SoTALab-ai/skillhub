package com.iflytek.skillhub.auth.policy;

import com.iflytek.skillhub.auth.oauth.OAuthClaims;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows Feishu login only when the tenant selected for the current OAuth flow is allowlisted.
 *
 * <p>The tenant is read from the current {@code user_info} response on every login. It is not
 * inferred from the user's stable subject, so one person belonging to multiple enterprises cannot
 * reuse an allowed enterprise identity while authenticating through a different tenant.</p>
 */
public class FeishuTenantAllowlistAccessPolicy implements AccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(FeishuTenantAllowlistAccessPolicy.class);
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
            log.info("Feishu tenant access policy denied non-Feishu provider: {}", claims.provider());
            return AccessDecision.DENY;
        }

        Object rawTenantKey = claims.extra().get(TENANT_KEY_CLAIM);
        if (!(rawTenantKey instanceof String tenantKey) || tenantKey.isBlank()) {
            log.info("Feishu tenant access policy denied login with missing tenant_key");
            return AccessDecision.DENY;
        }

        String normalizedTenantKey = tenantKey.trim();
        AccessDecision decision = allowedTenantKeys.contains(normalizedTenantKey)
                ? AccessDecision.ALLOW
                : AccessDecision.DENY;
        log.info("Feishu tenant access policy evaluated tenant_key={} decision={}",
                normalizedTenantKey, decision);
        return decision;
    }
}
