package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.RoutePoint;
import com.example.capstone_server.dto.RouteRequest;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class CandidateRouteGenerator {
    private final TmapPedestrianRouteClient tmapPedestrianRouteClient;
    private final OsmPbfRouteGenerator osmPbfRouteGenerator;
    private final ExecutorService tmapRouteExecutor;
    private final boolean tmapEnabled;
    private final boolean tmapFallbackWhenOsmInsufficient;
    private final boolean tmapDisabledFallbackEnabled;
    private final int minOsmCandidateCount;
    private final int maxAdditionalTmapCandidates;
    private final long tmapCandidateTimeoutSeconds;

    public CandidateRouteGenerator(
            TmapPedestrianRouteClient tmapPedestrianRouteClient,
            OsmPbfRouteGenerator osmPbfRouteGenerator,
            @Value("${capstone.routing.tmap.enabled:true}") boolean tmapEnabled,
            @Value("${capstone.routing.tmap.parallelism:8}") int tmapParallelism,
            @Value("${capstone.routing.tmap.fallback-when-osm-insufficient:true}") boolean tmapFallbackWhenOsmInsufficient,
            @Value("${capstone.routing.tmap.disabled-fallback-enabled:false}") boolean tmapDisabledFallbackEnabled,
            @Value("${capstone.routing.tmap.min-osm-candidates:3}") int minOsmCandidateCount,
            @Value("${capstone.routing.tmap.max-additional-candidates:4}") int maxAdditionalTmapCandidates,
            @Value("${capstone.routing.tmap.candidate-timeout-seconds:7}") long tmapCandidateTimeoutSeconds
    ) {
        this.tmapPedestrianRouteClient = tmapPedestrianRouteClient;
        this.osmPbfRouteGenerator = osmPbfRouteGenerator;
        this.tmapRouteExecutor = Executors.newFixedThreadPool(Math.max(1, tmapParallelism));
        this.tmapEnabled = tmapEnabled;
        this.tmapFallbackWhenOsmInsufficient = tmapFallbackWhenOsmInsufficient;
        this.tmapDisabledFallbackEnabled = tmapDisabledFallbackEnabled;
        this.minOsmCandidateCount = Math.max(1, minOsmCandidateCount);
        this.maxAdditionalTmapCandidates = Math.max(0, maxAdditionalTmapCandidates);
        this.tmapCandidateTimeoutSeconds = Math.max(1, tmapCandidateTimeoutSeconds);
    }

    public List<CandidateRoute> generate(RoutePoint start, RoutePoint end, RouteRequest request) {
        double directDistance = GeoMath.distanceMeters(start, end);
        double directBearing = GeoMath.bearingDegrees(start, end);
        double leftBearing = GeoMath.normalizeDegrees(directBearing - 90.0);
        double rightBearing = GeoMath.normalizeDegrees(directBearing + 90.0);

        double narrowOffset = clamp(directDistance * 0.12, 18.0, 55.0);
        double mediumOffset = clamp(directDistance * 0.18, 30.0, 85.0);

        Map<String, CandidateRoute> deduped = new LinkedHashMap<>();

        for (CandidateRoute osmRoute : osmPbfRouteGenerator.generate(start, end, request)) {
            deduped.putIfAbsent(routeKey(osmRoute.points()), osmRoute);
        }

        if (deduped.size() >= minOsmCandidateCount) {
            return new ArrayList<>(deduped.values());
        }

        if (!tmapFallbackWhenOsmInsufficient || (!tmapEnabled && !tmapDisabledFallbackEnabled)) {
            return new ArrayList<>(deduped.values());
        }

        List<RouteBuildRequest> routeRequests = new ArrayList<>();

        routeRequests.add(
                new RouteBuildRequest(
                        "shortest",
                        "shortest",
                        List.of(),
                        0.00
                )
        );

        List<RoutePattern> patterns = List.of(
                new RoutePattern(
                        "balanced-left-mid",
                        "balanced-left-mid",
                        List.of(
                                anchor(0.50, leftBearing, narrowOffset)
                        ),
                        0.05
                ),

                new RoutePattern(
                        "balanced-right-mid",
                        "balanced-right-mid",
                        List.of(
                                anchor(0.50, rightBearing, narrowOffset)
                        ),
                        0.05
                ),

                new RoutePattern(
                        "shade-left-soft",
                        "shade-left-soft",
                        List.of(
                                anchor(0.30, leftBearing, mediumOffset),
                                anchor(0.72, leftBearing, mediumOffset)
                        ),
                        0.10
                ),

                new RoutePattern(
                        "shade-right-soft",
                        "shade-right-soft",
                        List.of(
                                anchor(0.30, rightBearing, mediumOffset),
                                anchor(0.72, rightBearing, mediumOffset)
                        ),
                        0.10
                )
        );

        for (RoutePattern pattern : patterns.stream()
                .limit(maxAdditionalTmapCandidates)
                .toList()) {

            routeRequests.add(
                    new RouteBuildRequest(
                            pattern.id(),
                            pattern.sourceLabel(),
                            toWaypoints(start, end, pattern.anchors()),
                            pattern.shadeBias()
                    )
            );
        }

        List<CompletableFuture<CandidateRoute>> futures = routeRequests.stream()
                .map(routeRequest -> CompletableFuture.supplyAsync(
                                        () -> tmapPedestrianRouteClient.buildRoute(
                                                routeRequest.id(),
                                                routeRequest.sourceLabel(),
                                                start,
                                                end,
                                                routeRequest.waypoints(),
                                                routeRequest.shadeBias()
                                        ),
                                        tmapRouteExecutor
                                )
                                .completeOnTimeout(
                                        null,
                                        tmapCandidateTimeoutSeconds,
                                        TimeUnit.SECONDS
                                )
                                .exceptionally(ignored -> null)
                )
                .toList();

        for (CompletableFuture<CandidateRoute> future : futures) {
            addCandidate(deduped, future.join());
        }

        return new ArrayList<>(deduped.values());
    }

    @PreDestroy
    public void shutdownExecutor() throws InterruptedException {
        tmapRouteExecutor.shutdown();

        if (!tmapRouteExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            tmapRouteExecutor.shutdownNow();
        }
    }

    private void addCandidate(Map<String, CandidateRoute> deduped, CandidateRoute route) {
        if (route == null || route.points() == null || route.points().isEmpty()) {
            return;
        }

        deduped.putIfAbsent(routeKey(route.points()), route);
    }

    private List<RoutePoint> toWaypoints(
            RoutePoint start,
            RoutePoint end,
            List<RouteAnchor> anchors
    ) {
        List<RoutePoint> waypoints = new ArrayList<>();

        for (RouteAnchor anchor : anchors) {
            RoutePoint basePoint = GeoMath.interpolate(start, end, anchor.ratio());

            waypoints.add(
                    GeoMath.offset(
                            basePoint,
                            anchor.bearingDegrees(),
                            anchor.offsetMeters()
                    )
            );
        }

        return waypoints;
    }

    private RouteAnchor anchor(
            double ratio,
            double bearingDegrees,
            double offsetMeters
    ) {
        return new RouteAnchor(ratio, bearingDegrees, offsetMeters);
    }

    private String routeKey(List<RoutePoint> points) {
        StringBuilder builder = new StringBuilder();

        for (RoutePoint point : points) {
            if (!builder.isEmpty()) {
                builder.append('|');
            }

            builder.append(Math.round(point.getLat() * 10000.0) / 10000.0)
                    .append(',')
                    .append(Math.round(point.getLon() * 10000.0) / 10000.0);
        }

        return builder.toString();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record RoutePattern(
            String id,
            String sourceLabel,
            List<RouteAnchor> anchors,
            double shadeBias
    ) {
    }

    private record RouteAnchor(
            double ratio,
            double bearingDegrees,
            double offsetMeters
    ) {
    }

    private record RouteBuildRequest(
            String id,
            String sourceLabel,
            List<RoutePoint> waypoints,
            double shadeBias
    ) {
        private RouteBuildRequest {
            waypoints = waypoints == null
                    ? List.of()
                    : Collections.unmodifiableList(waypoints);
        }
    }
}