package com.iflytek.skillhub.search;

import java.util.Set;

/**
 * Caller visibility context used by search implementations to filter results consistently.
 */
public record SearchVisibilityScope(
        String userId,
        Set<Long> memberNamespaceIds,
        Set<Long> adminNamespaceIds,
        boolean platformWideAccess,
        boolean anonymousGlobalAccessEnabled
) {
    public SearchVisibilityScope(
            String userId,
            Set<Long> memberNamespaceIds,
            Set<Long> adminNamespaceIds,
            boolean platformWideAccess) {
        this(userId, memberNamespaceIds, adminNamespaceIds, platformWideAccess, true);
    }

    public SearchVisibilityScope(
            String userId,
            Set<Long> memberNamespaceIds,
            Set<Long> adminNamespaceIds) {
        this(userId, memberNamespaceIds, adminNamespaceIds, false, true);
    }

    public static SearchVisibilityScope anonymous() {
        return anonymous(true);
    }

    public static SearchVisibilityScope anonymous(boolean anonymousGlobalAccessEnabled) {
        return new SearchVisibilityScope(null, Set.of(), Set.of(), false, anonymousGlobalAccessEnabled);
    }
}
