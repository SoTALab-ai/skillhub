package com.iflytek.skillhub.auth.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.iflytek.skillhub.auth.oauth.OAuthClaims;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccessPolicyFactoryBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(AccessPolicyFactory.class)
            .withPropertyValues(
                    "skillhub.access-policy.mode=FEISHU_TENANT_ALLOWLIST",
                    "skillhub.access-policy.allowed-feishu-tenant-keys=tenant-zhihu,tenant-subsidiary");

    @Test
    void bindsCommaSeparatedFeishuTenantKeys() {
        contextRunner.run(context -> {
            AccessPolicy policy = context.getBean(AccessPolicy.class);

            assertThat(policy.evaluate(feishuClaims("tenant-zhihu")))
                    .isEqualTo(AccessDecision.ALLOW);
            assertThat(policy.evaluate(feishuClaims("tenant-subsidiary")))
                    .isEqualTo(AccessDecision.ALLOW);
            assertThat(policy.evaluate(feishuClaims("tenant-other")))
                    .isEqualTo(AccessDecision.DENY);
        });
    }

    private static OAuthClaims feishuClaims(String tenantKey) {
        return new OAuthClaims(
                "feishu",
                "shared-union-id",
                null,
                false,
                "user",
                Map.of("tenant_key", tenantKey));
    }
}
