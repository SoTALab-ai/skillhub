package com.iflytek.skillhub.auth.policy;

import com.iflytek.skillhub.auth.oauth.OAuthClaims;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class AccessPolicyTest {

    @Test
    void openPolicy_alwaysAllows() {
        var policy = new OpenAccessPolicy();
        var claims = new OAuthClaims("github", "123", "user@any.com", true, "user", Map.of());
        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.ALLOW);
    }

    @Test
    void emailDomainPolicy_allowsMatchingDomain() {
        var policy = new EmailDomainAccessPolicy(Set.of("company.com"));
        var claims = new OAuthClaims("github", "123", "user@company.com", true, "user", Map.of());
        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.ALLOW);
    }

    @Test
    void emailDomainPolicy_deniesNonMatchingDomain() {
        var policy = new EmailDomainAccessPolicy(Set.of("company.com"));
        var claims = new OAuthClaims("github", "123", "user@other.com", true, "user", Map.of());
        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.DENY);
    }

    @Test
    void emailDomainPolicy_deniesNullEmail() {
        var policy = new EmailDomainAccessPolicy(Set.of("company.com"));
        var claims = new OAuthClaims("github", "123", null, false, "user", Map.of());
        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.DENY);
    }

    @Test
    void emailDomainPolicy_deniesUnverifiedEmailFromMatchingDomain() {
        var policy = new EmailDomainAccessPolicy(Set.of("company.com"));
        var claims = new OAuthClaims("github", "123", "user@company.com", false, "user", Map.of());
        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.DENY);
    }

    @Test
    void providerAllowlistPolicy_allowsMatchingProvider() {
        var policy = new ProviderAllowlistAccessPolicy(Set.of("github"));
        var claims = new OAuthClaims("github", "123", "u@a.com", true, "user", Map.of());
        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.ALLOW);
    }

    @Test
    void providerAllowlistPolicy_deniesNonMatchingProvider() {
        var policy = new ProviderAllowlistAccessPolicy(Set.of("github"));
        var claims = new OAuthClaims("google", "123", "u@a.com", true, "user", Map.of());
        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.DENY);
    }

    @Test
    void feishuTenantAllowlistPolicy_allowsCurrentLoginFromAllowedTenant() {
        var policy = new FeishuTenantAllowlistAccessPolicy(Set.of("tenant-zhihu"));
        var claims = feishuClaims("shared-union-id", "tenant-zhihu");

        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.ALLOW);
    }

    @Test
    void feishuTenantAllowlistPolicy_deniesSameAccountWhenCurrentLoginUsesAnotherEnterprise() {
        var policy = new FeishuTenantAllowlistAccessPolicy(Set.of("tenant-zhihu"));
        var zhihuLogin = feishuClaims("shared-union-id", "tenant-zhihu");
        var otherEnterpriseLogin = feishuClaims("shared-union-id", "tenant-other");

        assertThat(policy.evaluate(zhihuLogin)).isEqualTo(AccessDecision.ALLOW);
        assertThat(policy.evaluate(otherEnterpriseLogin)).isEqualTo(AccessDecision.DENY);
    }

    @Test
    void feishuTenantAllowlistPolicy_deniesMissingTenantKey() {
        var policy = new FeishuTenantAllowlistAccessPolicy(Set.of("tenant-zhihu"));
        var claims = new OAuthClaims("feishu", "123", null, false, "user", Map.of());

        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.DENY);
    }

    @Test
    void feishuTenantAllowlistPolicy_deniesNonFeishuProviderEvenWithMatchingTenantKey() {
        var policy = new FeishuTenantAllowlistAccessPolicy(Set.of("tenant-zhihu"));
        var claims = new OAuthClaims(
                "github", "123", null, false, "user", Map.of("tenant_key", "tenant-zhihu"));

        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.DENY);
    }

    @Test
    void accessPolicyFactory_buildsFeishuTenantAllowlistFromConfiguration() {
        var factory = new AccessPolicyFactory();
        factory.setMode("FEISHU_TENANT_ALLOWLIST");
        factory.setAllowedFeishuTenantKeys(List.of("tenant-zhihu"));
        AccessPolicy policy = factory.accessPolicy();

        assertThat(policy.evaluate(feishuClaims("123", "tenant-zhihu")))
                .isEqualTo(AccessDecision.ALLOW);
        assertThat(policy.evaluate(feishuClaims("123", "tenant-other")))
                .isEqualTo(AccessDecision.DENY);
    }

    @Test
    void subjectWhitelistPolicy_allowsMatchingSubject() {
        var policy = new SubjectWhitelistAccessPolicy(Set.of("github:12345"));
        var claims = new OAuthClaims("github", "12345", "u@a.com", true, "user", Map.of());
        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.ALLOW);
    }

    @Test
    void subjectWhitelistPolicy_deniesNonMatchingSubject() {
        var policy = new SubjectWhitelistAccessPolicy(Set.of("github:12345"));
        var claims = new OAuthClaims("github", "99999", "u@a.com", true, "user", Map.of());
        assertThat(policy.evaluate(claims)).isEqualTo(AccessDecision.DENY);
    }

    private static OAuthClaims feishuClaims(String subject, String tenantKey) {
        return new OAuthClaims(
                "feishu",
                subject,
                null,
                false,
                "user",
                Map.of("tenant_key", tenantKey));
    }
}
