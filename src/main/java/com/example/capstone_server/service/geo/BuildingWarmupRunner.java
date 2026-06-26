package com.example.capstone_server.service.geo;

import com.example.capstone_server.service.ShadowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class BuildingWarmupRunner {
    private final ShadowService shadowService;
    private final boolean enabled;
    private final String regionsValue;

    public BuildingWarmupRunner(ShadowService shadowService,
                                @Value("${capstone.local-db.building-warmup.enabled:false}") boolean enabled,
                                @Value("${capstone.local-db.building-warmup.regions:}") String regionsValue) {
        this.shadowService = shadowService;
        this.enabled = enabled;
        this.regionsValue = regionsValue;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpAfterStartup() {
        if (!enabled || regionsValue == null || regionsValue.isBlank()) {
            return;
        }

        List<WarmupRegion> regions = parseRegions(regionsValue);
        if (regions.isEmpty()) {
            return;
        }

        CompletableFuture.runAsync(() -> warmUp(regions));
    }

    private void warmUp(List<WarmupRegion> regions) {
        long startedAt = System.currentTimeMillis();
        System.out.printf("Building DB warm-up started for %,d regions%n", regions.size());
        int totalBuildings = 0;
        for (WarmupRegion region : regions) {
            totalBuildings += shadowService.warmUpBuildingBounds(
                    region.name(),
                    region.minLat(),
                    region.minLon(),
                    region.maxLat(),
                    region.maxLon()
            );
        }
        System.out.printf(
                "Building DB warm-up finished in %d ms: %,d buildings across %,d regions%n",
                System.currentTimeMillis() - startedAt,
                totalBuildings,
                regions.size()
        );
    }

    private List<WarmupRegion> parseRegions(String value) {
        List<WarmupRegion> regions = new ArrayList<>();
        for (String rawRegion : value.split(";")) {
            String[] parts = rawRegion.split(",");
            if (parts.length != 5) {
                continue;
            }
            try {
                regions.add(new WarmupRegion(
                        parts[0].trim(),
                        Double.parseDouble(parts[1].trim()),
                        Double.parseDouble(parts[2].trim()),
                        Double.parseDouble(parts[3].trim()),
                        Double.parseDouble(parts[4].trim())
                ));
            } catch (NumberFormatException ignored) {
            }
        }
        return regions;
    }

    private record WarmupRegion(String name, double minLat, double minLon, double maxLat, double maxLon) {
    }
}
