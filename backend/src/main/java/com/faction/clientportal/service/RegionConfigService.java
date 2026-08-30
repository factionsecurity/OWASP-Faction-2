package com.faction.clientportal.service;

import com.faction.clientportal.model.RegionConfig;
import com.faction.clientportal.repository.RegionConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RegionConfigService {

    private final RegionConfigRepository regionConfigRepository;

    private static final String SINGLETON_ID = "singleton";

    public static final List<String> DEFAULT_REGIONS = List.of(
        "Global",
        "North America",
        "Latin America",
        "EMEA",
        "Europe",
        "Middle East",
        "Africa",
        "APAC",
        "ANZ",
        "South Asia",
        "East Asia",
        "Southeast Asia"
    );

    public List<String> getRegions() {
        return regionConfigRepository.findById(SINGLETON_ID)
                .map(RegionConfig::getRegions)
                .orElse(DEFAULT_REGIONS);
    }

    public List<String> updateRegions(List<String> regions) {
        RegionConfig config = regionConfigRepository.findById(SINGLETON_ID)
                .orElse(RegionConfig.builder().id(SINGLETON_ID).build());
        config.setRegions(regions);
        return regionConfigRepository.save(config).getRegions();
    }

    public void ensureDefaults() {
        if (regionConfigRepository.findById(SINGLETON_ID).isEmpty()) {
            regionConfigRepository.save(RegionConfig.builder()
                    .id(SINGLETON_ID)
                    .regions(new ArrayList<>(DEFAULT_REGIONS))
                    .build());
        }
    }
}
