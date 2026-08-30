package com.faction.clientportal.edition;

import com.faction.clientportal.dto.EditionStatusDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles the capability report the frontend gates every paid feature on.
 */
@Service
public class EditionStatusService {

    /**
     * No outbound link by default.
     *
     * <p>This is an OWASP project, and a commercial URL baked into it would appear only in
     * the open source build — the one place it reads as the project advertising a vendor
     * rather than a product describing itself. An operator who wants somewhere to point
     * sets {@code FACTION_UPGRADE_URL}; the UI renders no link at all when it is blank.
     */
    public static final String DEFAULT_UPGRADE_URL = "";

    private final EditionPolicy editionPolicy;
    private final QuotaUsageService quotaUsageService;
    private final String upgradeUrl;

    // Explicit constructor rather than @RequiredArgsConstructor: the upgrade URL is a
    // @Value parameter, and field injection would leave it null under plain unit tests.
    public EditionStatusService(EditionPolicy editionPolicy,
                                QuotaUsageService quotaUsageService,
                                @Value("${faction.upgrade-url:" + DEFAULT_UPGRADE_URL + "}") String upgradeUrl) {
        this.editionPolicy = editionPolicy;
        this.quotaUsageService = quotaUsageService;
        this.upgradeUrl = upgradeUrl;
    }

    public EditionStatusDto status() {
        Map<String, Boolean> features = new LinkedHashMap<>();
        for (Feature feature : Feature.values()) {
            features.put(feature.getKey(), editionPolicy.enabled(feature));
        }

        // Unlimited quotas are omitted rather than sent as MAX_VALUE: an absent key reads
        // as "no limit" in the UI, where a very large number would render as one.
        Map<String, Integer> limits = new LinkedHashMap<>();
        Map<String, Long> usage = new LinkedHashMap<>();
        for (Quota quota : Quota.values()) {
            if (editionPolicy.isLimited(quota)) {
                limits.put(quota.getKey(), editionPolicy.limit(quota));
            }
            usage.put(quota.getKey(), quotaUsageService.current(quota));
        }

        return EditionStatusDto.builder()
                .edition(editionPolicy.edition().name())
                .features(features)
                .limits(limits)
                .usage(usage)
                .upgradeUrl(upgradeUrl)
                .build();
    }

    public String getUpgradeUrl() {
        return upgradeUrl;
    }
}
