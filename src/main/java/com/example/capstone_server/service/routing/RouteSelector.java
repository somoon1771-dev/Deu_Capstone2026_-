package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.RouteOption;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class RouteSelector {
    private static final double SAFE_MAX_DETOUR_RATIO = 1.4;
    private static final double SAFE_DETOUR_PENALTY_WEIGHT = 0.5;

    private final RouteQualityFilter routeQualityFilter;

    public RouteSelector(RouteQualityFilter routeQualityFilter) {
        this.routeQualityFilter = routeQualityFilter;
    }

    public List<RouteOption> select(List<EvaluatedRoute> evaluatedRoutes) {
        if (evaluatedRoutes.isEmpty()) {
            return List.of();
        }

        EvaluatedRoute shortest = shortestRoute(evaluatedRoutes);

        List<RouteOption> selected = new ArrayList<>();
        selected.add(toOption("SHORTEST", shortest));

        routeQualityFilter.shadeCandidates(evaluatedRoutes).stream()
                .filter(route -> isDistinct(selected, route))
                .findFirst()
                .or(() -> routeQualityFilter.bestFallbackShade(evaluatedRoutes)
                        .filter(route -> isDistinct(selected, route)))
                .ifPresent(route -> selected.add(toOption("SHADE", route)));

        routeQualityFilter.balancedCandidates(evaluatedRoutes).stream()
                .filter(route -> isDistinct(selected, route))
                .findFirst()
                .or(() -> routeQualityFilter.bestFallbackBalanced(evaluatedRoutes)
                        .filter(route -> isDistinct(selected, route)))
                .ifPresent(route -> selected.add(toOption("BALANCED", route)));

        selectSafeRoutePreferDistinct(evaluatedRoutes, shortest, selected)
                .ifPresent(route -> selected.add(toOption("SAFE", route)));

        fillMissingRouteTypes(selected, evaluatedRoutes);

        return selected;
    }

    /**
     * 비, 구름 많음, 야간처럼 그늘 경로가 의미 없는 상황에서 사용.
     * SHORTEST + SAFE만 반환한다.
     */
    public List<RouteOption> selectShortestAndSafe(List<EvaluatedRoute> evaluatedRoutes) {
        if (evaluatedRoutes.isEmpty()) {
            return List.of();
        }

        EvaluatedRoute shortest = shortestRoute(evaluatedRoutes);

        List<RouteOption> selected = new ArrayList<>();
        selected.add(toOption("SHORTEST", shortest));

        selectSafeRoutePreferDistinct(evaluatedRoutes, shortest, selected)
                .ifPresent(route -> selected.add(toOption("SAFE", route)));

        return selected;
    }

    public List<RouteOption> selectShortestOnly(List<EvaluatedRoute> evaluatedRoutes) {
        if (evaluatedRoutes.isEmpty()) {
            return List.of();
        }

        return List.of(toOption("SHORTEST", shortestRoute(evaluatedRoutes)));
    }

    private EvaluatedRoute shortestRoute(List<EvaluatedRoute> evaluatedRoutes) {
        return evaluatedRoutes.stream()
                .min(Comparator.comparingDouble(EvaluatedRoute::distanceMeters))
                .orElse(evaluatedRoutes.get(0));
    }

    /**
     * 1순위: 현재 선택된 경로들과 다른 SAFE 후보
     * 2순위: 다른 후보가 없으면 SHORTEST와 같은 경로라도 SAFE로 표시
     */
    private Optional<EvaluatedRoute> selectSafeRoutePreferDistinct(List<EvaluatedRoute> evaluatedRoutes,
                                                                   EvaluatedRoute shortest,
                                                                   List<RouteOption> selected) {
        double shortestDistance = Math.max(shortest.distanceMeters(), 1.0);

        Optional<EvaluatedRoute> distinctSafe = evaluatedRoutes.stream()
                .filter(route -> isReasonableSafeDetour(route, shortestDistance))
                .filter(route -> isDistinct(selected, route))
                .max(Comparator
                        .comparingDouble(this::safeRankScore)
                        .thenComparing(Comparator.comparingDouble(EvaluatedRoute::distanceMeters).reversed()));

        if (distinctSafe.isPresent()) {
            return distinctSafe;
        }

        return evaluatedRoutes.stream()
                .filter(route -> isReasonableSafeDetour(route, shortestDistance))
                .max(Comparator
                        .comparingDouble(this::safeRankScore)
                        .thenComparing(Comparator.comparingDouble(EvaluatedRoute::distanceMeters).reversed()));
    }

    private boolean isReasonableSafeDetour(EvaluatedRoute route, double shortestDistance) {
        double detourRatio = route.distanceMeters() / shortestDistance;
        return detourRatio <= SAFE_MAX_DETOUR_RATIO;
    }

    private double safeRankScore(EvaluatedRoute route) {
        double detourPenalty = Math.max(0.0, route.detourRatio() - 1.0) * SAFE_DETOUR_PENALTY_WEIGHT;
        return route.safetyScore() - detourPenalty;
    }

    private void fillMissingRouteTypes(List<RouteOption> selected, List<EvaluatedRoute> evaluatedRoutes) {
        List<String> missingTypes = new ArrayList<>(List.of("SHADE", "BALANCED"));

        missingTypes.removeIf(type -> selected.stream()
                .anyMatch(route -> type.equals(route.getRouteType())));

        List<EvaluatedRoute> fallbacks = evaluatedRoutes.stream()
                .filter(route -> !"shortest".equals(route.candidate().id()))
                .filter(route -> isDistinct(selected, route))
                .sorted(Comparator
                        .comparingDouble(EvaluatedRoute::totalScore).reversed()
                        .thenComparing(Comparator.comparingDouble(EvaluatedRoute::shadeScore).reversed()))
                .toList();

        for (EvaluatedRoute route : fallbacks) {
            if (missingTypes.isEmpty()) {
                return;
            }

            selected.add(toOption(missingTypes.remove(0), route));
        }
    }

    private RouteOption toOption(String routeType, EvaluatedRoute route) {
        System.out.println(
                routeType
                        + " | source=" + route.candidate().source()
                        + " | zigzag=" + route.zigzagScore()
                        + " | detour=" + route.detourRatio()
                        + " | progress=" + route.progressEfficiency()
                        + " | regression=" + route.maxRegressionRatio()
                        + " | score=" + route.totalScore()
                        + " | shade=" + route.shadeScore()
                        + " | safety=" + route.safetyScore()
                        + " | cctv=" + route.cctvCount()
        );

        return new RouteOption(
                routeType,
                (int) Math.round(route.distanceMeters()),
                route.shadeScore(),
                route.distanceScore(),
                route.totalScore(),
                route.weatherComfortScore(),
                route.heatStressScore(),
                route.longestSunExposureMeters(),
                route.sunExposurePenalty(),
                route.detourRatio(),
                route.progressEfficiency(),
                route.safetyScore(),
                route.cctvCount(),
                route.nearbyCctvs(),
                route.candidate().source(),
                route.candidate().points(),
                route.segmentScores()
        );
    }

    private boolean isDistinct(List<RouteOption> selected, EvaluatedRoute candidate) {
        for (RouteOption option : selected) {
            if (samePath(option, candidate)) {
                return false;
            }
        }

        return true;
    }

    private boolean samePath(RouteOption option, EvaluatedRoute candidate) {
        if (option.getPoints().size() != candidate.candidate().points().size()) {
            return false;
        }

        double distanceGap = Math.abs(option.getDistance() - candidate.distanceMeters())
                / Math.max(option.getDistance(), 1.0);
        double shadeGap = Math.abs(option.getShadeScore() - candidate.shadeScore());

        return distanceGap < 0.03 && shadeGap < 0.03;
    }
}
